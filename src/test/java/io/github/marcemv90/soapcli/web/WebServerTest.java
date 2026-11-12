package io.github.marcemv90.soapcli.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.marcemv90.soapcli.testsupport.MockServer;
import io.github.marcemv90.soapcli.testsupport.MockServer.Reply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerTest {

    private static final String ENV11 = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><ping/></s:Body></s:Envelope>";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY).build();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private WebServer start(String... args) throws IOException {
        return start(new HashMap<>(), args);
    }

    private WebServer start(Map<String, String> env, String... args) throws IOException {
        List<String> all = new ArrayList<>(List.of("--port", "0"));
        all.addAll(List.of(args));
        Map<String, String> environment = new HashMap<>(env);
        environment.putIfAbsent("SOAPCLI_PROFILE_FILE", tmp.resolve("no-profiles.json").toString());
        ServeOptions options = ServeOptions.parse(all.toArray(new String[0]), environment, tmp.toString());
        WebServer server = new WebServer(options, new PrintStream(logBytes, true, StandardCharsets.UTF_8));
        server.start();
        toClose.add(server);
        return server;
    }

    private MockServer mock(java.util.function.Function<MockServer.Received, Reply> handler) {
        MockServer m = new MockServer(handler);
        toClose.add(m);
        return m;
    }

    private HttpResponse<String> get(WebServer s, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path)).GET();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(WebServer s, String path, String contentType, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postJson(WebServer s, String path, Object body) throws Exception {
        return JSON.readTree(post(s, path, "application/json", JSON.writeValueAsString(body)).body());
    }

    private static Map<String, Object> sendBody(String endpoint) {
        Map<String, Object> m = new HashMap<>();
        m.put("endpoint", endpoint);
        m.put("body", ENV11);
        return m;
    }

    /** A raw HTTP exchange, for what HttpClient will not let us do (a forged Host header, a path with dots). */
    private String raw(WebServer s, String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), s.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    // ---- static files and security headers ------------------------------------------------------------------------

    @Test
    void servesTheEmbeddedUiWithStrictSecurityHeaders() throws Exception {
        WebServer s = start();
        HttpResponse<String> r = get(s, "/");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertTrue(r.body().contains("id=\"root\""));
        String csp = r.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("script-src 'self'"), csp);
        assertFalse(csp.contains("unsafe-eval"), csp);
        assertFalse(csp.contains("script-src 'self' 'unsafe-inline'"), csp);
        assertTrue(csp.contains("connect-src 'self'"), "the page may only call its own server: " + csp);
        assertFalse(csp.contains("connect-src http"), csp);
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(null));
        assertEquals("DENY", r.headers().firstValue("X-Frame-Options").orElse(null));
        assertEquals("no-referrer", r.headers().firstValue("Referrer-Policy").orElse(null));
    }

    @Test
    void servesHashedAssetsWithLongCaching() throws Exception {
        WebServer s = start();
        Matcher m = Pattern.compile("(?:src|href)=\"\\./(assets/[^\"]+\\.js)\"").matcher(get(s, "/").body());
        assertTrue(m.find(), "index.html must reference its script");
        HttpResponse<String> js = get(s, "/" + m.group(1));
        assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("Content-Type").orElse("").startsWith("text/javascript"));
        assertTrue(js.headers().firstValue("Cache-Control").orElse("").contains("immutable"));
        assertEquals(200, get(s, "/theme-init.js").statusCode());
        assertEquals(200, get(s, "/favicon.svg").statusCode());
    }

    @Test
    void refusesPathsOutsideTheUi() throws Exception {
        WebServer s = start();
        assertEquals(404, get(s, "/nothing.js").statusCode());
        assertEquals(404, get(s, "/assets/").statusCode());
        assertEquals(404, get(s, "/assets").statusCode());
        assertEquals(404, get(s, "/index.html.bak").statusCode());
        for (String path : new String[]{"/assets/../../../pom.xml", "/assets/%2e%2e/%2e%2e/pom.xml", "/%2e%2e/pom.xml",
                "/assets/..%2f..%2fpom.xml", "/./index.html", "/web/index.html", "//etc/passwd"}) {
            String answer = raw(s, "GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
            assertFalse(answer.startsWith("HTTP/1.1 200"), path + " -> " + answer.split("\r\n")[0]);
        }
        assertEquals("text/html; charset=utf-8", WebServer.staticType("index.html"));
        assertNull(WebServer.staticType("../pom.xml"));
        assertNull(WebServer.staticType("a/./b.js"));
        assertNull(WebServer.staticType("noextension"));
        assertNull(WebServer.staticType("evil.exe"));
    }

    @Test
    void onlyGetAndPostAreAllowed() throws Exception {
        WebServer s = start();
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/"))
                .method("DELETE", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(405, r.statusCode());
        assertEquals(405, post(s, "/", "application/json", "{}").statusCode());
        assertEquals(405, get(s, "/api/send").statusCode());
        assertEquals(404, get(s, "/api/unknown").statusCode());
    }

    @Test
    void healthAndInfo() throws Exception {
        WebServer s = start("--allow-host", "a.example.com", "--no-insecure-tls");
        assertEquals("ok", get(s, "/healthz").body());
        JsonNode info = JSON.readTree(get(s, "/api/info").body());
        assertNotNull(info.get("version"));
        assertEquals("a.example.com", info.get("allowedHosts").get(0).asText());
        assertFalse(info.get("insecureTlsAllowed").asBoolean());
        assertFalse(info.get("profilesFileFound").asBoolean());
    }

    // ---- who may talk to the server -------------------------------------------------------------------------------

    @Test
    void aForgedHostHeaderIsRefusedWhenListeningOnLoopback() throws Exception {
        WebServer s = start();
        String evil = raw(s, "GET / HTTP/1.1\r\nHost: evil.example.com\r\nConnection: close\r\n\r\n");
        assertTrue(evil.startsWith("HTTP/1.1 403"), evil.split("\r\n")[0]); // DNS rebinding
        for (String host : new String[]{"localhost", "localhost:8080", "127.0.0.1", "[::1]:9"}) {
            assertTrue(raw(s, "GET / HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").startsWith("HTTP/1.1 200"), host);
        }
        assertTrue(WebServer.loopbackHost("LOCALHOST:1"));
        assertFalse(WebServer.loopbackHost("localhost.evil.com"));
        assertFalse(WebServer.loopbackHost("127evil.com"));
        assertFalse(WebServer.loopbackHost(null));
    }

    @Test
    void theLoginProtectsEverythingExceptTheHealthCheck() throws Exception {
        WebServer s = start("--ui-user", "alice", "--ui-password", "s3cret");
        assertEquals("ok", get(s, "/healthz").body());
        HttpResponse<String> denied = get(s, "/");
        assertEquals(401, denied.statusCode());
        assertTrue(denied.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Basic"));
        assertEquals(401, get(s, "/api/info").statusCode());
        assertEquals(401, post(s, "/api/send", "application/json", "{}").statusCode());

        String wrong = "Basic " + Base64.getEncoder().encodeToString("alice:nope".getBytes(StandardCharsets.UTF_8));
        assertEquals(401, get(s, "/", "Authorization", wrong).statusCode());
        assertEquals(401, get(s, "/", "Authorization", "Basic !!!not-base64").statusCode());
        assertEquals(401, get(s, "/", "Authorization", "Bearer abc").statusCode());

        String right = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".getBytes(StandardCharsets.UTF_8));
        assertEquals(200, get(s, "/", "Authorization", right).statusCode());
        assertEquals(200, get(s, "/api/info", "Authorization", right).statusCode());
    }

    @Test
    void postsMustBeJsonSoOtherSitesCannotUseTheApi() throws Exception {
        WebServer s = start();
        try (MockServer target = mock(r -> Reply.xml("<ok/>"))) {
            String body = JSON.writeValueAsString(sendBody(target.url("/")));
            // a cross-site <form> or a "simple" fetch can only send these types; none may reach the engine
            assertEquals(415, post(s, "/api/send", "text/plain", body).statusCode());
            assertEquals(415, post(s, "/api/send", "application/x-www-form-urlencoded", body).statusCode());
            assertEquals(415, post(s, "/api/send", null, body).statusCode());
            assertTrue(target.requests.isEmpty());
            assertEquals(200, post(s, "/api/send", "application/json; charset=utf-8", body).statusCode());
        }
    }

    // ---- sending --------------------------------------------------------------------------------------------------

    @Test
    void sendsARequestAndReturnsAFormattedAnswer() throws Exception {
        WebServer s = start();
        try (MockServer target = mock(r -> Reply.xml("<a><b>1</b></a>").header("X-Trace", "t1"))) {
            Map<String, Object> req = sendBody(target.url("/soap"));
            req.put("soapAction", "urn:x");
            req.put("headers", Map.of("X-Env", "acc"));
            JsonNode r = postJson(s, "/api/send", req);

            assertEquals(200, r.get("status").asInt());
            assertEquals("<a>\n  <b>1</b>\n</a>\n", r.get("body").asText());
            assertEquals("<a><b>1</b></a>", r.get("rawBody").asText());
            assertEquals("1.1", r.get("request").get("soapVersion").asText());
            MockServer.Received got = target.requests.get(0);
            assertEquals("text/xml; charset=utf-8", got.header("Content-Type"));
            assertEquals("\"urn:x\"", got.header("SOAPAction"));
            assertEquals("acc", got.header("X-Env"));
            assertEquals(ENV11, got.bodyText());
        }
    }

    @Test
    void aSoapFaultIsDataNotAnApiError() throws Exception {
        WebServer s = start();
        try (MockServer target = mock(r -> Reply.xml("<Fault>boom</Fault>").status(500))) {
            HttpResponse<String> http = post(s, "/api/send", "application/json", JSON.writeValueAsString(sendBody(target.url("/"))));
            assertEquals(200, http.statusCode());
            JsonNode r = JSON.readTree(http.body());
            assertEquals(500, r.get("status").asInt());
            assertEquals("<Fault>boom</Fault>\n", r.get("body").asText());
        }
    }

    @Test
    void credentialsAreSentButNeverReturned() throws Exception {
        WebServer s = start();
        try (MockServer target = mock(r -> Reply.xml("<ok/>").header("Set-Cookie", "sid=topsecret"))) {
            Map<String, Object> req = sendBody(target.url("/"));
            req.put("authBasic", "alice:S3cr3t!");
            String answer = post(s, "/api/send", "application/json", JSON.writeValueAsString(req)).body();

            assertEquals("Basic " + Base64.getEncoder().encodeToString("alice:S3cr3t!".getBytes(StandardCharsets.UTF_8)),
                    target.requests.get(0).header("Authorization"));
            assertFalse(answer.contains("S3cr3t"), answer);
            assertFalse(answer.contains(Base64.getEncoder().encodeToString("alice:S3cr3t!".getBytes(StandardCharsets.UTF_8))), answer);
            assertFalse(answer.contains("topsecret"), answer);
            assertTrue(answer.contains("Basic ****"), answer);
            assertFalse(logBytes.toString(StandardCharsets.UTF_8).contains("S3cr3t"), "the access log must not hold credentials");
        }
    }

    @Test
    void placeholdersAndEncodingsWork() throws Exception {
        WebServer s = start();
        try (MockServer target = mock(r -> Reply.xml("<ok/>"))) {
            Map<String, Object> req = sendBody(target.url("/"));
            req.put("body", "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + ENV11.replace("<ping/>", "<n>___N:x___ ñ</n>"));
            req.put("args", Map.of("N", "7"));
            postJson(s, "/api/send", req);
            byte[] sent = target.requests.get(0).body;
            assertTrue(new String(sent, StandardCharsets.ISO_8859_1).contains("<n>7 \u00f1</n>"), "sent as ISO-8859-1");
            assertTrue(target.requests.get(0).header("Content-Type").contains("iso-8859-1"));
        }
    }

    @Test
    void bodiesInOtherCharsetsAreDecoded() throws Exception {
        WebServer s = start();
        byte[] latin1 = "<a>ñandú</a>".getBytes(StandardCharsets.ISO_8859_1);
        try (MockServer target = mock(r -> new Reply().bytes(latin1, "text/xml; charset=ISO-8859-1"))) {
            assertEquals("<a>ñandú</a>\n", postJson(s, "/api/send", sendBody(target.url("/"))).get("body").asText());
        }
    }

    @Test
    void binaryAnswersAreOfferedAsBase64() throws Exception {
        WebServer s = start();
        byte[] data = {0, 1, 2, (byte) 255};
        try (MockServer target = mock(r -> new Reply().bytes(data, "multipart/related; boundary=b"))) {
            JsonNode r = postJson(s, "/api/send", sendBody(target.url("/")));
            assertTrue(r.get("binary").asBoolean());
            assertNull(r.get("body"));
            assertEquals(Base64.getEncoder().encodeToString(data), r.get("bodyBase64").asText());
        }
    }

    @Test
    void attachmentsAreInlinedOrSentAsMtomAndTheirTempFilesRemoved() throws Exception {
        WebServer s = start();
        int before = tempDirs();
        try (MockServer target = mock(r -> Reply.xml("<ok/>"))) {
            Map<String, Object> inline = sendBody(target.url("/"));
            inline.put("body", ENV11.replace("<ping/>", "<v>cid:f</v>"));
            inline.put("attachments", List.of(Map.of("cid", "f", "filename", "../../evil.txt",
                    "contentBase64", Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)))));
            postJson(s, "/api/send", inline);
            assertTrue(target.requests.get(0).bodyText().contains("<v>aGVsbG8=</v>"));

            Map<String, Object> mtom = new HashMap<>(inline);
            mtom.put("mtom", true);
            postJson(s, "/api/send", mtom);
            String multipart = new String(target.requests.get(1).body, StandardCharsets.ISO_8859_1);
            assertTrue(target.requests.get(1).header("Content-Type").startsWith("multipart/related"));
            assertTrue(multipart.contains("<inc:Include href=\"cid:f\""), multipart);
            assertTrue(multipart.contains("Content-ID: <f>\r\nContent-Transfer-Encoding: binary\r\n\r\nhello\r\n"), multipart);
        }
        assertEquals(before, tempDirs(), "uploaded attachments must not stay on disk");
    }

    @Test
    void badAttachmentsAreRejectedWithoutLeavingFiles() throws Exception {
        WebServer s = start();
        int before = tempDirs();
        Map<String, Object> req = sendBody("http://127.0.0.1:1/");
        req.put("attachments", List.of(
                Map.of("cid", "ok", "filename", "a", "contentBase64", "aGk="),
                Map.of("cid", "bad cid", "filename", "b", "contentBase64", "aGk=")));
        HttpResponse<String> r = post(s, "/api/send", "application/json", JSON.writeValueAsString(req));
        assertEquals(400, r.statusCode());
        assertEquals("config", JSON.readTree(r.body()).get("error").get("category").asText());

        req.put("attachments", List.of(Map.of("cid", "ok", "filename", "a", "contentBase64", "***not base64***")));
        assertEquals(400, post(s, "/api/send", "application/json", JSON.writeValueAsString(req)).statusCode());
        assertEquals(before, tempDirs());
    }

    private int tempDirs() throws IOException {
        try (Stream<Path> list = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return (int) list.filter(p -> p.getFileName().toString().startsWith("soapcli-web-")).count();
        }
    }

    @Test
    void previewShowsTheRequestWithoutSendingIt() throws Exception {
        WebServer s = start();
        try (MockServer target = mock(r -> Reply.xml("<ok/>"))) {
            Map<String, Object> req = sendBody(target.url("/"));
            req.put("authBearer", "tok-123");
            JsonNode p = postJson(s, "/api/preview", req);
            assertTrue(target.requests.isEmpty());
            assertEquals("1.1", p.get("soapVersion").asText());
            assertTrue(p.toString().contains("Bearer ****"));
            assertFalse(p.toString().contains("tok-123"));
            assertTrue(p.get("previewXml").asText().contains("<s:Envelope"));
        }
    }

    @Test
    void formatsXml() throws Exception {
        WebServer s = start();
        assertEquals("<a>\n  <b>1</b>\n</a>\n", postJson(s, "/api/format", Map.of("xml", "<a><b>1</b></a>")).get("xml").asText());
        assertEquals("<a><b></a>", postJson(s, "/api/format", Map.of("xml", "<a><b></a>")).get("xml").asText());
    }

    // ---- errors ---------------------------------------------------------------------------------------------------

    @Test
    void mistakesAreReportedWithTheirCategory() throws Exception {
        WebServer s = start();
        HttpResponse<String> noEndpoint = post(s, "/api/send", "application/json", "{\"body\":\"<a/>\"}");
        assertEquals(400, noEndpoint.statusCode());
        assertEquals("config", JSON.readTree(noEndpoint.body()).get("error").get("category").asText());
        assertTrue(noEndpoint.body().contains("endpoint"));

        HttpResponse<String> badJson = post(s, "/api/send", "application/json", "{not json");
        assertEquals(400, badJson.statusCode());
        assertTrue(badJson.body().contains("Invalid JSON"));

        assertEquals(400, post(s, "/api/send", "application/json", "{\"endpoint\":\"http://127.0.0.1:1/\",\"body\":\"  \"}").statusCode());
        assertEquals(400, post(s, "/api/send", "application/json", "{\"endpoint\":\"file:///etc/hostname\",\"body\":\"<a/>\"}").statusCode());

        HttpResponse<String> down = post(s, "/api/send", "application/json", JSON.writeValueAsString(sendBody("http://127.0.0.1:1/")));
        assertEquals(502, down.statusCode());
        JsonNode error = JSON.readTree(down.body()).get("error");
        assertEquals("network", error.get("category").asText());
        assertTrue(error.get("message").asText().startsWith("Could not connect"), error.get("message").asText());
        assertFalse(down.body().contains("\tat "), "no stack traces in API errors");
    }

    @Test
    void unexpectedFailuresDoNotLeakInternals() throws Exception {
        WebServer s = start();
        HttpResponse<String> r = post(s, "/api/send", "application/json", "{\"endpoint\":\"http://127.0.0.1:1/\",\"body\":\"<a/>\",\"soapVersion\":\"9.9\"}");
        assertEquals(400, r.statusCode()); // a bad soapVersion is a config error, not a crash
        assertTrue(r.body().contains("SOAP version"));
    }

    // ---- limits and policy ----------------------------------------------------------------------------------------

    @Test
    void theAllowListStopsCallsToOtherHosts() throws Exception {
        WebServer s = start("--allow-host", "only.example.com");
        try (MockServer target = mock(r -> Reply.xml("<ok/>"))) {
            HttpResponse<String> r = post(s, "/api/send", "application/json", JSON.writeValueAsString(sendBody(target.url("/"))));
            assertEquals(403, r.statusCode());
            JsonNode error = JSON.readTree(r.body()).get("error");
            assertEquals("forbidden", error.get("category").asText());
            assertTrue(error.get("message").asText().contains("only.example.com"));
            assertTrue(target.requests.isEmpty());
        }
        WebServer open = start("--allow-host", "127.0.0.1");
        try (MockServer target = mock(r -> Reply.xml("<ok/>"))) {
            assertEquals(200, post(open, "/api/send", "application/json", JSON.writeValueAsString(sendBody(target.url("/")))).statusCode());
        }
    }

    @Test
    void theAllowListCannotBeBypassedByARedirect() throws Exception {
        WebServer s = start("--allow-host", "127.0.0.1");
        try (MockServer inside = mock(r -> Reply.xml("<inside/>"));
             MockServer origin = mock(r -> new Reply().status(302).header("Location", "http://localhost:" + inside.port() + "/"))) {
            Map<String, Object> req = sendBody(origin.url("/"));
            req.put("followRedirects", true);
            HttpResponse<String> r = post(s, "/api/send", "application/json", JSON.writeValueAsString(req));
            assertEquals(403, r.statusCode());
            assertTrue(inside.requests.isEmpty());
        }
    }

    @Test
    void skippingTlsChecksCanBeDisabledByTheAdministrator() throws Exception {
        WebServer s = start("--no-insecure-tls");
        Map<String, Object> req = sendBody("https://127.0.0.1:1/");
        req.put("insecure", true);
        HttpResponse<String> r = post(s, "/api/send", "application/json", JSON.writeValueAsString(req));
        assertEquals(403, r.statusCode());
        assertTrue(r.body().contains("disabled"));
    }

    /**
     * The server answers before the whole body has been sent. If it closed the connection with unread data the TCP reset
     * could destroy the 413 and the client would only see "connection reset": the body has to be well beyond what the
     * JDK server drains by itself (64 KB) and what loopback buffers swallow, so that this fails without the fix.
     */
    private static final int WAY_TOO_BIG = 16 * 1024 * 1024;

    @Test
    void oversizedRequestsAreRefused() throws Exception {
        WebServer s = start("--max-body-mb", "1");
        String big = "{\"endpoint\":\"http://127.0.0.1:1/\",\"body\":\"" + "x".repeat(WAY_TOO_BIG) + "\"}";
        HttpResponse<String> r = post(s, "/api/send", "application/json", big);
        assertEquals(413, r.statusCode());
        assertEquals("too-large", JSON.readTree(r.body()).get("error").get("category").asText());
    }

    @Test
    void oversizedRequestsWithoutAContentLengthAreRefusedToo() throws Exception {
        WebServer s = start("--max-body-mb", "1");
        byte[] big = ("{\"endpoint\":\"http://127.0.0.1:1/\",\"body\":\"" + "x".repeat(WAY_TOO_BIG) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        // an input stream has no known length, so the client sends it chunked and the server cannot tell from the headers
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/api/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(big)))
                .build();
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, r.statusCode());
        assertEquals("too-large", JSON.readTree(r.body()).get("error").get("category").asText());
    }

    @Test
    void oversizedAnswersAreRefused() throws Exception {
        WebServer s = start("--max-response-mb", "1");
        try (MockServer target = mock(r -> new Reply().bytes(new byte[2 * 1024 * 1024], "text/plain"))) {
            HttpResponse<String> r = post(s, "/api/send", "application/json", JSON.writeValueAsString(sendBody(target.url("/"))));
            assertEquals(502, r.statusCode());
            assertTrue(r.body().contains("larger than the limit"), r.body());
        }
    }

    // ---- profiles -------------------------------------------------------------------------------------------------

    private Path profiles(String json) throws IOException {
        Path file = tmp.resolve("profiles.json");
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void profilesAreListedWithTheirSecretsMasked() throws Exception {
        Path file = profiles("{\"profiles\":{\"acc\":{\"endpoint\":\"http://x/soap\",\"authBasic\":\"alice:hunter2\","
                + "\"keystore\":{\"path\":\"k.p12\",\"password\":\"topsecret\"},\"requestFile\":\"r.xml\"},"
                + "\"loop\":{\"extends\":\"loop\"}}}");
        WebServer s = start(Map.of("SOAPCLI_PROFILE_FILE", file.toString()));
        String listing = get(s, "/api/profiles").body();
        assertFalse(listing.contains("hunter2") || listing.contains("topsecret"), listing);
        JsonNode root = JSON.readTree(listing);
        assertTrue(root.get("found").asBoolean());
        assertEquals("acc", root.get("profiles").get(0).get("name").asText());
        assertEquals("****", root.get("profiles").get(0).get("settings").get("authBasic").asText());
        assertTrue(root.get("profiles").get(0).get("hasRequestFile").asBoolean());
        assertNotNull(root.get("profiles").get(1).get("error"), "a broken profile must not hide the others");
    }

    @Test
    void aProfileSuppliesTheEndpointAndSecretsFromTheServerEnvironment() throws Exception {
        try (MockServer target = mock(r -> Reply.xml("<ok/>"))) {
            Path file = profiles("{\"profiles\":{\"acc\":{\"endpoint\":\"" + target.url("/soap") + "\","
                    + "\"authBasic\":\"${ACC_CREDS}\",\"headers\":{\"X-Env\":\"acc\"},\"soapVersion\":\"1.2\"}}}");
            WebServer s = start(Map.of("SOAPCLI_PROFILE_FILE", file.toString(), "ACC_CREDS", "svc:pw"));

            Map<String, Object> req = new HashMap<>();
            req.put("profile", "acc");
            req.put("body", ENV11);
            req.put("headers", Map.of("X-Extra", "1"));
            String answer = post(s, "/api/send", "application/json", JSON.writeValueAsString(req)).body();

            MockServer.Received got = target.requests.get(0);
            assertEquals("/soap", got.path);
            assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:pw".getBytes(StandardCharsets.UTF_8)), got.header("Authorization"));
            assertEquals("acc", got.header("X-Env"));
            assertEquals("1", got.header("X-Extra"));
            assertTrue(got.header("Content-Type").startsWith("application/soap+xml"));
            assertFalse(answer.contains("svc:pw"));
        }
    }

    @Test
    void aProfileWithAMissingEnvironmentVariableIsAConfigError() throws Exception {
        Path file = profiles("{\"profiles\":{\"acc\":{\"endpoint\":\"http://x/\",\"authBasic\":\"${NOT_SET_ANYWHERE}\"}}}");
        WebServer s = start(Map.of("SOAPCLI_PROFILE_FILE", file.toString()));
        HttpResponse<String> r = post(s, "/api/send", "application/json", "{\"profile\":\"acc\",\"body\":\"<a/>\"}");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("NOT_SET_ANYWHERE"), r.body());
        assertEquals(400, post(s, "/api/send", "application/json", "{\"profile\":\"ghost\",\"body\":\"<a/>\"}").statusCode());
    }

    @Test
    void aProfilesRequestFilePrefillsTheEditor() throws Exception {
        Path xml = tmp.resolve("default-request.xml");
        Files.write(xml, ("\uFEFF" + ENV11).getBytes(StandardCharsets.UTF_8));
        Path file = profiles("{\"profiles\":{\"acc\":{\"endpoint\":\"http://x/\",\"requestFile\":\"" + xml.toString().replace("\\", "\\\\") + "\"},"
                + "\"none\":{\"endpoint\":\"http://x/\"}}}");
        WebServer s = start(Map.of("SOAPCLI_PROFILE_FILE", file.toString()));
        HttpResponse<String> r = get(s, "/api/profiles/acc/request");
        assertEquals(200, r.statusCode());
        assertEquals(ENV11, r.body(), "the BOM is dropped");
        assertEquals(404, get(s, "/api/profiles/none/request").statusCode());
        assertEquals(400, get(s, "/api/profiles/ghost/request").statusCode());
    }

    @Test
    void withoutAProfilesFileTheListIsSimplyEmpty() throws Exception {
        WebServer s = start();
        JsonNode root = JSON.readTree(get(s, "/api/profiles").body());
        assertFalse(root.get("found").asBoolean());
        assertEquals(0, root.get("profiles").size());
    }

    @Test
    void healthcheckCommandTellsHealthyFromDown() throws Exception {
        WebServer s = start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertEquals(0, ServeCommand.healthcheck(new String[]{"--port", String.valueOf(s.port())}, new HashMap<>(),
                new PrintStream(out), new PrintStream(err)));
        assertEquals("ok", out.toString(StandardCharsets.UTF_8).trim());
        assertEquals(1, ServeCommand.healthcheck(new String[]{"--port", "1"}, new HashMap<>(), new PrintStream(out), new PrintStream(err)));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("unhealthy"));
        assertEquals(0, ServeCommand.healthcheck(new String[0], Map.of("SOAPCLI_PORT", String.valueOf(s.port())), new PrintStream(out), new PrintStream(err)));
    }
}
