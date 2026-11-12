package io.github.marcemv90.soapcli.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.marcemv90.soapcli.core.ConfigException;
import io.github.marcemv90.soapcli.core.HostNotAllowedException;
import io.github.marcemv90.soapcli.core.TransportException;
import io.github.marcemv90.soapcli.web.ApiModel.AttachmentCheckRequest;
import io.github.marcemv90.soapcli.web.ApiModel.DeleteRequest;
import io.github.marcemv90.soapcli.web.ApiModel.ErrorResponse;
import io.github.marcemv90.soapcli.web.ApiModel.FormatRequest;
import io.github.marcemv90.soapcli.web.ApiModel.FormatResponse;
import io.github.marcemv90.soapcli.web.ApiModel.SavePlaceholderRequest;
import io.github.marcemv90.soapcli.web.ApiModel.SaveProfileRequest;
import io.github.marcemv90.soapcli.web.ApiModel.SendError;
import io.github.marcemv90.soapcli.web.ApiModel.SendRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * The web UI and its JSON API, on the JDK's built-in HTTP server (no extra dependency).
 *
 * <p>Security model, in the order requests meet it:
 * <ol>
 *   <li>When listening on a loopback address only, the {@code Host} header must be a loopback name: this stops DNS
 *       rebinding, where a hostile web page reaches a service on your machine through a name it controls.</li>
 *   <li>Everything but {@code /healthz} needs the configured user and password, if any.</li>
 *   <li>{@code POST} requests must be {@code application/json}. A browser cannot send that cross-site without a CORS
 *       preflight, which this server never approves, so other sites cannot make your browser call the API.</li>
 *   <li>Responses carry a strict Content-Security-Policy; the page loads no third-party resources.</li>
 * </ol>
 */
public final class WebServer implements AutoCloseable {

    private static final int THREADS = 16;
    /** Only plain relative paths made of these characters are ever looked up in the jar. */
    private static final Pattern SAFE_PATH = Pattern.compile("[A-Za-z0-9._\\-]+(/[A-Za-z0-9._\\-]+)*");
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();
    static {
        CONTENT_TYPES.put("html", "text/html; charset=utf-8");
        CONTENT_TYPES.put("js", "text/javascript; charset=utf-8");
        CONTENT_TYPES.put("mjs", "text/javascript; charset=utf-8");
        CONTENT_TYPES.put("css", "text/css; charset=utf-8");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("ico", "image/x-icon");
        CONTENT_TYPES.put("woff2", "font/woff2");
        CONTENT_TYPES.put("woff", "font/woff");
        CONTENT_TYPES.put("json", "application/json; charset=utf-8");
        CONTENT_TYPES.put("txt", "text/plain; charset=utf-8");
    }
    /**
     * The page only ever talks to this server (the calls to the services are made by the server, not by the browser),
     * so connect-src is 'self'; scripts may only come from this server either, which is what keeps injected content from
     * running or from sending anything elsewhere. Styles allow inline because the editor and the pop-ups set theirs at
     * run time.
     */
    private static final String CSP = "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; base-uri 'none'; "
            + "form-action 'none'; frame-ancestors 'none'";

    /** One HTTP answer, built by a route and written by {@link #write}. */
    private static final class Response {
        final int status;
        final String contentType;
        final byte[] body;
        final Map<String, String> headers = new LinkedHashMap<>();

        Response(int status, String contentType, byte[] body) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
        }
    }

    private final ServeOptions options;
    private final PrintStream log;
    private final ApiService api;
    private final ObjectMapper json = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, byte[]> statics = new ConcurrentHashMap<>();
    private final byte[] expectedLogin;
    private HttpServer server;
    private ExecutorService executor;

    public WebServer(ServeOptions options, PrintStream log) {
        this.options = options;
        this.log = log;
        this.api = new ApiService(options);
        this.expectedLogin = options.hasAuth()
                ? (options.uiUser + ":" + options.uiPassword).getBytes(StandardCharsets.UTF_8) : null;
        if (loadResource("index.html") == null) {
            throw new IllegalStateException("The web UI is missing from the jar (web/index.html)");
        }
    }

    /** A file of the embedded UI, or {@code null}. Callers must have checked the name with {@link #SAFE_PATH}. */
    private static byte[] loadResource(String name) {
        try (InputStream in = WebServer.class.getResourceAsStream("/web/" + name)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(options.host, options.port), 0);
        executor = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "soapcli-web");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::dispatch);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ---- request handling -----------------------------------------------------------------------------------------

    private void dispatch(HttpExchange ex) throws IOException {
        long started = System.nanoTime();
        Response response;
        try {
            response = route(ex);
        } catch (Throwable t) {
            synchronized (log) {
                log.println("[" + Instant.now() + "] internal error on " + ex.getRequestMethod() + " "
                        + ex.getRequestURI().getPath() + ": " + t);
                t.printStackTrace(log);
            }
            response = error(500, "internal", "Unexpected error. The details are in the server log.");
        }
        write(ex, response);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/healthz")) {
            synchronized (log) {
                log.println("[" + Instant.now() + "] " + ex.getRequestMethod() + " " + path + " "
                        + response.status + " " + millis + "ms");
            }
        }
    }

    private Response route(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();

        if (options.isLoopbackOnly() && !loopbackHost(ex.getRequestHeaders().getFirst("Host"))) {
            return error(403, "forbidden", "This server only answers to localhost names.");
        }
        if (!method.equals("GET") && !method.equals("POST")) {
            Response r = error(405, "unsupported", "Method not allowed");
            r.headers.put("Allow", "GET, POST");
            return r;
        }
        if (path.equals("/healthz")) {
            return method.equals("GET") ? text(200, "text/plain; charset=utf-8", "ok") : error(405, "unsupported", "Use GET");
        }
        if (expectedLogin != null && !authorized(ex)) {
            Response r = error(401, "forbidden", "Authentication required");
            r.headers.put("WWW-Authenticate", "Basic realm=\"SoapCLI\", charset=\"UTF-8\"");
            return r;
        }
        if (path.startsWith("/api/")) {
            return handleApi(ex, method, path);
        }
        return handleStatic(method, path);
    }

    private Response handleApi(HttpExchange ex, String method, String path) throws IOException {
        try {
            switch (path) {
                case "/api/info":
                    return requireGet(method) != null ? requireGet(method) : jsonResponse(200, api.info());
                case "/api/profiles":
                    return requireGet(method) != null ? requireGet(method) : jsonResponse(200, api.profiles());
                case "/api/placeholders":
                    return requireGet(method) != null ? requireGet(method) : jsonResponse(200, api.placeholders());
                case "/api/profiles/save": {
                    Response bad = requireJsonPost(ex, method);
                    if (bad != null) {
                        return bad;
                    }
                    return jsonResponse(200, api.saveProfile(readJson(ex, SaveProfileRequest.class)));
                }
                case "/api/profiles/delete": {
                    Response bad = requireJsonPost(ex, method);
                    if (bad != null) {
                        return bad;
                    }
                    return jsonResponse(200, api.deleteProfile(readJson(ex, DeleteRequest.class)));
                }
                case "/api/placeholders/save": {
                    Response bad = requireJsonPost(ex, method);
                    if (bad != null) {
                        return bad;
                    }
                    return jsonResponse(200, api.savePlaceholder(readJson(ex, SavePlaceholderRequest.class)));
                }
                case "/api/placeholders/delete": {
                    Response bad = requireJsonPost(ex, method);
                    if (bad != null) {
                        return bad;
                    }
                    return jsonResponse(200, api.deletePlaceholder(readJson(ex, DeleteRequest.class)));
                }
                case "/api/send": {
                    Response bad = requireJsonPost(ex, method);
                    if (bad != null) {
                        return bad;
                    }
                    SendRequest request = readJson(ex, SendRequest.class);
                    return jsonResponse(200, api.send(request));
                }
                case "/api/preview": {
                    Response bad = requireJsonPost(ex, method);
                    if (bad != null) {
                        return bad;
                    }
                    SendRequest request = readJson(ex, SendRequest.class);
                    return jsonResponse(200, api.preview(request));
                }
                case "/api/attachments/check": {
                    Response bad = requireJsonPost(ex, method);
                    if (bad != null) {
                        return bad;
                    }
                    AttachmentCheckRequest request = readJson(ex, AttachmentCheckRequest.class);
                    return jsonResponse(200, api.checkAttachment(request.path));
                }
                case "/api/format": {
                    Response bad = requireJsonPost(ex, method);
                    if (bad != null) {
                        return bad;
                    }
                    FormatRequest request = readJson(ex, FormatRequest.class);
                    FormatResponse response = new FormatResponse();
                    response.xml = api.format(request.xml);
                    return jsonResponse(200, response);
                }
                default:
                    if (path.startsWith("/api/profiles/") && path.endsWith("/definition")) {
                        if (requireGet(method) != null) {
                            return requireGet(method);
                        }
                        String name = URLDecoder.decode(
                                path.substring("/api/profiles/".length(), path.length() - "/definition".length()),
                                StandardCharsets.UTF_8);
                        return jsonResponse(200, api.profileDefinition(name));
                    }
                    if (path.startsWith("/api/profiles/") && path.endsWith("/request")) {
                        if (requireGet(method) != null) {
                            return requireGet(method);
                        }
                        String name = URLDecoder.decode(
                                path.substring("/api/profiles/".length(), path.length() - "/request".length()),
                                StandardCharsets.UTF_8);
                        return text(200, "text/xml; charset=utf-8", api.profileRequest(name));
                    }
                    return error(404, "unsupported", "Unknown API endpoint");
            }
        } catch (SendError e) {
            return error(e.httpStatus, e.category, e.getMessage());
        } catch (HostNotAllowedException e) {
            return error(403, "forbidden", e.getMessage());
        } catch (ConfigException e) {
            return error(400, "config", e.getMessage());
        } catch (TransportException e) {
            return error(502, "network", e.getMessage());
        }
    }

    private Response requireGet(String method) {
        return method.equals("GET") ? null : error(405, "unsupported", "Use GET");
    }

    /** POSTs must be JSON, which also keeps cross-site form posts out. Returns an error response, or {@code null}. */
    private Response requireJsonPost(HttpExchange ex, String method) {
        if (!method.equals("POST")) {
            return error(405, "unsupported", "Use POST");
        }
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            return error(415, "unsupported", "The Content-Type must be application/json");
        }
        String length = ex.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try {
                if (Long.parseLong(length.trim()) > options.maxBodyBytes) {
                    discardBody(ex);
                    return tooLarge();
                }
            } catch (NumberFormatException ignored) {
                // the body is read with a cap anyway
            }
        }
        return null;
    }

    /** Most of a request body we are willing to read and throw away just to be able to answer it properly. */
    private static final long DISCARD_LIMIT = 512L * 1024 * 1024;

    /**
     * Reads and discards what is left of the request body (up to {@link #DISCARD_LIMIT}) before an early answer such as
     * "too large". Closing a connection that still has unread data makes the operating system send a TCP reset, which
     * can destroy the answer before the client has read it: the browser would then report a network error instead of
     * the 413 and its explanation. The JDK server only drains 64 KB by itself.
     */
    private static void discardBody(HttpExchange ex) {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try {
            InputStream in = ex.getRequestBody();
            int n;
            while (total < DISCARD_LIMIT && (n = in.read(buffer)) >= 0) {
                total += n;
            }
        } catch (IOException ignored) {
            // the client went away: nothing left to answer
        }
    }

    private Response tooLarge() {
        return error(413, "too-large", "The request is larger than " + (options.maxBodyBytes / (1024 * 1024))
                + " MB (--max-body-mb).");
    }

    private <T> T readJson(HttpExchange ex, Class<T> type) throws IOException {
        byte[] bytes = ex.getRequestBody().readNBytes((int) Math.min(options.maxBodyBytes + 1, Integer.MAX_VALUE - 8));
        if (bytes.length > options.maxBodyBytes) {
            discardBody(ex);
            throw new SendError(413, "too-large", "The request is larger than "
                    + (options.maxBodyBytes / (1024 * 1024)) + " MB (--max-body-mb).");
        }
        try {
            T value = json.readValue(bytes, type);
            if (value == null) {
                throw new SendError(400, "config", "The request body is empty");
            }
            return value;
        } catch (IOException e) {
            throw new SendError(400, "config", "Invalid JSON: " + firstLine(e.getMessage()));
        }
    }

    private Response handleStatic(String method, String path) {
        if (!method.equals("GET")) {
            return error(405, "unsupported", "Use GET");
        }
        String name = path.equals("/") ? "index.html" : path.substring(1);
        String type = staticType(name);
        if (type == null) {
            return error(404, "unsupported", "Not found");
        }
        byte[] content = statics.get(name);
        if (content == null) {
            content = loadResource(name);
            if (content == null) {
                return error(404, "unsupported", "Not found");
            }
            statics.put(name, content);
        }
        Response r = new Response(200, type, content);
        // Vite puts a content hash in the names under assets/, so those can be cached for good.
        r.headers.put("Cache-Control", name.startsWith("assets/") ? "public, max-age=31536000, immutable" : "no-cache");
        return r;
    }

    /** The content type of a servable file name, or {@code null} if the name is not acceptable. */
    static String staticType(String name) {
        if (!SAFE_PATH.matcher(name).matches()) {
            return null;
        }
        String last = name.substring(name.lastIndexOf('/') + 1);
        for (String segment : name.split("/")) {
            if (segment.matches("\\.+")) {
                return null; // "." and ".." segments
            }
        }
        int dot = last.lastIndexOf('.');
        return dot <= 0 ? null : CONTENT_TYPES.get(last.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    // ---- security helpers -----------------------------------------------------------------------------------------

    private boolean authorized(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        try {
            byte[] given = Base64.getDecoder().decode(header.substring(6).trim());
            return MessageDigest.isEqual(given, expectedLogin); // constant time
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    static boolean loopbackHost(String hostHeader) {
        if (hostHeader == null) {
            return false;
        }
        String host = hostHeader.trim().toLowerCase(Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end < 0 ? host : host.substring(0, end + 1);
        } else {
            int colon = host.lastIndexOf(':');
            host = colon < 0 ? host : host.substring(0, colon);
        }
        return host.equals("localhost") || host.equals("[::1]") || host.startsWith("127.");
    }

    // ---- responses ------------------------------------------------------------------------------------------------

    private Response jsonResponse(int status, Object value) throws IOException {
        Response r = new Response(status, "application/json; charset=utf-8", json.writeValueAsBytes(value));
        r.headers.put("Cache-Control", "no-store");
        return r;
    }

    private Response error(int status, String category, String message) {
        try {
            Response r = new Response(status, "application/json; charset=utf-8",
                    json.writeValueAsBytes(new ErrorResponse(category, message)));
            r.headers.put("Cache-Control", "no-store");
            return r;
        } catch (IOException e) {
            return new Response(status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Response text(int status, String contentType, String text) {
        Response r = new Response(status, contentType, text.getBytes(StandardCharsets.UTF_8));
        r.headers.put("Cache-Control", "no-store");
        return r;
    }

    private void write(HttpExchange ex, Response r) throws IOException {
        com.sun.net.httpserver.Headers headers = ex.getResponseHeaders();
        headers.set("Content-Type", r.contentType);
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        for (Map.Entry<String, String> h : r.headers.entrySet()) {
            headers.set(h.getKey(), h.getValue());
        }
        ex.sendResponseHeaders(r.status, r.body.length == 0 ? -1 : r.body.length);
        if (r.body.length > 0) {
            ex.getResponseBody().write(r.body);
        }
        ex.close();
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "";
        }
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
