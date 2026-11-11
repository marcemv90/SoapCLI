package io.github.marcemv90.soapcli.testsupport;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** A throw-away SOAP endpoint that records everything it receives. Uses only the JDK's built-in HTTP server. */
public final class MockServer implements AutoCloseable {

    public static final class Received {
        public final String method;
        public final String path;
        public final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        public final byte[] body;
        /** Subject of the client certificate (HTTPS with client auth), or {@code null}. */
        public String clientCertificate;

        Received(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = body;
        }

        public String header(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    public static final class Reply {
        public int status = 200;
        public String contentType = "text/xml; charset=utf-8";
        public byte[] body = new byte[0];
        public final Map<String, String> headers = new LinkedHashMap<>();
        public long delayMillis;

        public static Reply xml(String xml) {
            Reply r = new Reply();
            r.body = xml.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public Reply status(int status) {
            this.status = status;
            return this;
        }

        public Reply header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        public Reply delay(long millis) {
            this.delayMillis = millis;
            return this;
        }

        public Reply bytes(byte[] bytes, String contentType) {
            this.body = bytes;
            this.contentType = contentType;
            return this;
        }
    }

    public final List<Received> requests = new CopyOnWriteArrayList<>();
    private final HttpServer server;
    private final boolean secure;

    public MockServer(Function<Received, Reply> handler) {
        this(null, false, handler);
    }

    public MockServer(SSLContext tls, boolean needClientAuth, Function<Received, Reply> handler) {
        try {
            InetSocketAddress address = new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
            if (tls != null) {
                HttpsServer https = HttpsServer.create(address, 0);
                https.setHttpsConfigurator(new HttpsConfigurator(tls) {
                    @Override
                    public void configure(HttpsParameters params) {
                        SSLParameters p = getSSLContext().getDefaultSSLParameters();
                        p.setNeedClientAuth(needClientAuth);
                        params.setSSLParameters(p);
                    }
                });
                server = https;
            } else {
                server = HttpServer.create(address, 0);
            }
            secure = tls != null;
            server.createContext("/", exchange -> handle(exchange, handler));
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "mock-server");
                t.setDaemon(true);
                return t;
            }));
            server.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void handle(HttpExchange exchange, Function<Received, Reply> handler) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        Received received = new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(), body);
        for (Map.Entry<String, List<String>> h : exchange.getRequestHeaders().entrySet()) {
            received.headers.put(h.getKey(), h.getValue());
        }
        if (exchange instanceof HttpsExchange) {
            try {
                received.clientCertificate = ((HttpsExchange) exchange).getSSLSession().getPeerPrincipal().getName();
            } catch (Exception ignored) {
                // no client certificate
            }
        }
        requests.add(received);

        Reply reply = handler.apply(received);
        if (reply.delayMillis > 0) {
            try {
                Thread.sleep(reply.delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Headers out = exchange.getResponseHeaders();
        out.set("Content-Type", reply.contentType);
        for (Map.Entry<String, String> h : reply.headers.entrySet()) {
            out.add(h.getKey(), h.getValue());
        }
        exchange.sendResponseHeaders(reply.status, reply.body.length == 0 ? -1 : reply.body.length);
        if (reply.body.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(reply.body);
            }
        }
        exchange.close();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String url(String path) {
        return (secure ? "https" : "http") + "://127.0.0.1:" + port() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
