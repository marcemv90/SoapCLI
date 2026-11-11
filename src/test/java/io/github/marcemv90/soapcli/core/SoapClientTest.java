package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.marcemv90.soapcli.testsupport.MockServer;
import io.github.marcemv90.soapcli.testsupport.MockServer.Reply;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class SoapClientTest {

    private static final String ENV11 = RequestBuilderTest.ENV11;
    private static final String ENV12 = RequestBuilderTest.ENV12;
    private final SoapClient client = new SoapClient();

    private static CallConfig cfg(String url) {
        CallConfig c = new CallConfig();
        c.endpoint = url;
        return c;
    }

    private SoapResponse send(CallConfig c, String xml) {
        return client.send(RequestBuilder.build(c, xml, StandardCharsets.UTF_8, "ua"), c.transport);
    }

    @Test
    void customHeadersAreSent() {
        // Regression: --header used to fail with "IllegalStateException: Already connected" on every call.
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/soap"));
            c.headers.put("X-Correlation-ID", "12345");
            c.headers.put("SOAPAction", "\"urn:myAction\"");
            SoapResponse resp = send(c, ENV12);
            assertEquals(200, resp.status);
            MockServer.Received r = server.requests.get(0);
            assertEquals("12345", r.header("X-Correlation-ID"));
            assertEquals("\"urn:myAction\"", r.header("SOAPAction"));
            assertEquals("POST", r.method);
            assertEquals("/soap", r.path);
            assertEquals("ua", r.header("User-Agent"));
        }
    }

    @Test
    void soap11GoesOutAsTextXmlWithSoapAction() {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.soapAction = "urn:x";
            send(c, ENV11);
            MockServer.Received r = server.requests.get(0);
            assertEquals("text/xml; charset=utf-8", r.header("Content-Type"));
            assertEquals("\"urn:x\"", r.header("SOAPAction"));
            assertEquals(ENV11, r.bodyText());
        }
    }

    @Test
    void restrictedHeadersLikeHostCanBeOverridden() {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.headers.put("Host", "virtual.example.com");
            send(c, ENV12);
            assertEquals("virtual.example.com", server.requests.get(0).header("Host"));
        }
    }

    @Test
    void faultsComeBackWithStatusHeadersAndBody() {
        try (MockServer server = new MockServer(r -> Reply.xml("<Fault>boom</Fault>").status(500).header("X-Trace", "t1"))) {
            SoapResponse resp = send(cfg(server.url("/")), ENV12);
            assertEquals(500, resp.status);
            assertFalse(resp.isSuccess());
            assertEquals("<Fault>boom</Fault>", new String(resp.body, StandardCharsets.UTF_8));
            assertEquals("t1", resp.header("x-trace"));
            assertTrue(resp.contentType().startsWith("text/xml"));
            assertTrue(resp.elapsedMillis >= 0);
        }
    }

    @Test
    void emptyResponsesAreFine() {
        try (MockServer server = new MockServer(r -> new Reply().status(202))) {
            SoapResponse resp = send(cfg(server.url("/")), ENV12);
            assertEquals(202, resp.status);
            assertTrue(resp.isSuccess());
            assertEquals(0, resp.body.length);
        }
    }

    @Test
    void gzipResponsesAreDecompressed() throws IOException {
        ByteArrayOutputStream zipped = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(zipped)) {
            gz.write("<zipped/>".getBytes(StandardCharsets.UTF_8));
        }
        try (MockServer server = new MockServer(r -> new Reply().header("Content-Encoding", "gzip")
                .bytes(zipped.toByteArray(), "text/xml"))) {
            SoapResponse resp = send(cfg(server.url("/")), ENV12);
            assertEquals("<zipped/>", new String(resp.body, StandardCharsets.UTF_8));
        }
    }

    @Test
    void responseBytesAreReturnedUndecoded() {
        byte[] latin1 = "<a>ñandú</a>".getBytes(StandardCharsets.ISO_8859_1);
        try (MockServer server = new MockServer(r -> new Reply().bytes(latin1, "text/xml; charset=ISO-8859-1"))) {
            SoapResponse resp = send(cfg(server.url("/")), ENV12);
            assertArrayEquals(latin1, resp.body);
            assertEquals("<a>ñandú</a>", XmlCharsets.decode(resp.body, resp.contentType()).text);
        }
    }

    @Test
    void redirectsAreNotFollowedByDefault() {
        try (MockServer target = new MockServer(r -> Reply.xml("<landed/>"));
             MockServer origin = new MockServer(r -> new Reply().status(302).header("Location", target.url("/landed")))) {
            SoapResponse resp = send(cfg(origin.url("/soap")), ENV12);
            assertEquals(302, resp.status);
            assertTrue(resp.isRedirect());
            assertEquals(target.url("/landed"), resp.header("Location"));
            assertTrue(target.requests.isEmpty(), "the old code silently turned this POST into a GET on the new URL");
        }
    }

    @Test
    void followRedirectsResendsTheSamePostAndDropsCredentialsAcrossOrigins() {
        try (MockServer target = new MockServer(r -> Reply.xml("<landed/>"));
             MockServer origin = new MockServer(r -> new Reply().status(307).header("Location", target.url("/landed")))) {
            CallConfig c = cfg(origin.url("/soap"));
            c.transport.followRedirects = true;
            c.authBasic = "alice:secret";
            SoapResponse resp = send(c, ENV12);
            assertEquals(200, resp.status);
            assertEquals(target.url("/landed"), resp.uri.toString());
            MockServer.Received landed = target.requests.get(0);
            assertEquals("POST", landed.method);
            assertEquals(ENV12, landed.bodyText());
            assertNull(landed.header("Authorization"), "credentials must not follow a redirect to another origin");
            assertEquals(1, origin.requests.size());
            assertTrue(origin.requests.get(0).header("Authorization").startsWith("Basic "));
        }
    }

    @Test
    void sameOriginRedirectsKeepCredentials() {
        try (MockServer server = new MockServer(r -> r.path.equals("/soap")
                ? new Reply().status(302).header("Location", "/moved")
                : Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/soap"));
            c.transport.followRedirects = true;
            c.authBearer = "tok";
            SoapResponse resp = send(c, ENV12);
            assertEquals(200, resp.status);
            assertEquals("Bearer tok", server.requests.get(1).header("Authorization"));
        }
    }

    @Test
    void redirectLoopsAreCapped() {
        try (MockServer server = new MockServer(r -> new Reply().status(302).header("Location", "/again"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.followRedirects = true;
            SoapResponse resp = send(c, ENV12);
            assertEquals(302, resp.status);
            assertEquals(6, server.requests.size()); // first call + 5 redirects
        }
    }

    @Test
    void readTimeoutGivesAHelpfulMessage() {
        try (MockServer server = new MockServer(r -> Reply.xml("<slow/>").delay(2500))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.readTimeoutSeconds = 1;
            TransportException e = assertThrows(TransportException.class, () -> send(c, ENV12));
            assertTrue(e.getMessage().contains("--timeout"), e.getMessage());
        }
    }

    @Test
    void connectionRefusedGivesAHelpfulMessage() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = s.getLocalPort();
        }
        CallConfig c = cfg("http://127.0.0.1:" + port + "/");
        TransportException e = assertThrows(TransportException.class, () -> send(c, ENV12));
        assertTrue(e.getMessage().startsWith("Could not connect to 127.0.0.1:" + port), e.getMessage());
    }

    @Test
    void largeBodiesAreStreamed() throws IOException {
        // Above 8 MiB the body is streamed with a fixed length rather than buffered inside HttpURLConnection.
        StringBuilder big = new StringBuilder(ENV12.length() + 9_000_000);
        big.append(ENV12, 0, ENV12.indexOf("<s:Body>") + 8).append("<blob>");
        for (int i = 0; i < 9_000_000 / 10; i++) {
            big.append("0123456789");
        }
        big.append("</blob>").append(ENV12.substring(ENV12.indexOf("</s:Body>")));
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            SoapResponse resp = send(cfg(server.url("/")), big.toString());
            assertEquals(200, resp.status);
            assertEquals(big.length(), server.requests.get(0).body.length);
            assertEquals(String.valueOf(big.length()), server.requests.get(0).header("Content-Length"));
        }
    }

    @Test
    void mtomRequestArrivesAsWellFormedMultipart() throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("att", ".bin");
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            java.nio.file.Files.write(file, new byte[]{0, 1, 2, (byte) 0xFF, 10, 13, 10});
            CallConfig c = cfg(server.url("/"));
            c.mtom = true;
            c.attachments.put("bin1", file.toString());
            send(c, ENV12.replace("<ping/>", "<v>cid:bin1</v>"));

            MockServer.Received r = server.requests.get(0);
            String ct = r.header("Content-Type");
            String boundary = ct.substring(ct.indexOf("boundary=\"") + 10, ct.lastIndexOf('"'));
            String raw = new String(r.body, StandardCharsets.ISO_8859_1);
            assertTrue(raw.contains("<v><inc:Include href=\"cid:bin1\""));
            int start = raw.indexOf("Content-ID: <bin1>");
            int dataStart = raw.indexOf("\r\n\r\n", start) + 4;
            byte[] data = java.util.Arrays.copyOfRange(r.body, dataStart, dataStart + 7);
            assertArrayEquals(new byte[]{0, 1, 2, (byte) 0xFF, 10, 13, 10}, data, "binary data must arrive intact");
            assertTrue(raw.endsWith("--" + boundary + "--\r\n"));
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }
}
