package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.marcemv90.soapcli.testsupport.MockServer;
import io.github.marcemv90.soapcli.testsupport.MockServer.Reply;
import io.github.marcemv90.soapcli.testsupport.TestPki;
import io.github.marcemv90.soapcli.testsupport.TestProxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end TLS (server trust, mutual TLS, --insecure) and proxy authentication against real sockets. */
class TlsAndProxyTest {

    private static TestPki pki;
    private final SoapClient client = new SoapClient();

    @BeforeAll
    static void generatePki(@TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path dir) throws Exception {
        pki = new TestPki(java.nio.file.Files.createTempDirectory(dir, "pki"));
    }

    private SoapResponse send(CallConfig c) {
        return client.send(RequestBuilder.build(c, RequestBuilderTest.ENV12, StandardCharsets.UTF_8, "ua"), c.transport);
    }

    private static CallConfig cfg(String url) {
        CallConfig c = new CallConfig();
        c.endpoint = url;
        return c;
    }

    // ---- server certificate ---------------------------------------------------------------------------------------

    @Test
    void anUntrustedSelfSignedCertificateIsRejectedWithAHint() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), false, r -> Reply.xml("<ok/>"))) {
            TransportException e = assertThrows(TransportException.class, () -> send(cfg(server.url("/"))));
            assertTrue(e.getMessage().contains("not trusted"), e.getMessage());
            assertTrue(e.getMessage().contains("--truststore"), e.getMessage());
        }
    }

    @Test
    void aTruststoreMakesItTrusted() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), false, r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.tls.truststorePath = pki.clientTruststore.toString();
            c.transport.tls.truststorePassword = TestPki.PASSWORD;
            assertEquals(200, send(c).status);
        }
    }

    @Test
    void insecureSkipsCertificateAndHostnameVerification() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), false, r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/").replace("127.0.0.1", "localhost"));
            c.transport.tls.insecure = true;
            assertEquals(200, send(c).status);
        }
    }

    // ---- client certificate ---------------------------------------------------------------------------------------

    @Test
    void withoutAClientCertificateMutualTlsFails() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), true, r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.tls.truststorePath = pki.clientTruststore.toString();
            assertThrows(TransportException.class, () -> send(c));
        }
    }

    @Test
    void theClientCertificateIsPresentedAndTheAliasIsOptionalWhenThereIsOnlyOneKey() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), true, r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.tls.truststorePath = pki.clientTruststore.toString();
            c.transport.tls.keystorePath = pki.clientKeystore.toString(); // .p12 -> PKCS12 inferred
            c.transport.tls.keystorePassword = TestPki.PASSWORD;
            assertEquals(200, send(c).status);
            assertEquals("CN=test-client", server.requests.get(0).clientCertificate);
        }
    }

    @Test
    void aliasMatchingIsCaseInsensitive() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), true, r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.tls.truststorePath = pki.clientTruststore.toString();
            c.transport.tls.keystorePath = pki.clientKeystore.toString();
            c.transport.tls.keystorePassword = TestPki.PASSWORD;
            c.transport.tls.keystoreAlias = "CLIENT";
            assertEquals(200, send(c).status);
        }
    }

    @Test
    void aWrongAliasListsTheAvailableOnes() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), true, r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.tls.keystorePath = pki.twoKeyKeystore.toString();
            c.transport.tls.keystorePassword = TestPki.PASSWORD;
            c.transport.tls.keystoreAlias = "three";
            ConfigException e = assertThrows(ConfigException.class, () -> send(c));
            assertTrue(e.getMessage().contains("one") && e.getMessage().contains("two"), e.getMessage());
        }
    }

    @Test
    void severalKeysWithoutAnAliasAreAmbiguous() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), true, r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.tls.keystorePath = pki.twoKeyKeystore.toString();
            c.transport.tls.keystorePassword = TestPki.PASSWORD;
            ConfigException e = assertThrows(ConfigException.class, () -> send(c));
            assertTrue(e.getMessage().contains("--keystore-alias"), e.getMessage());
        }
    }

    @Test
    void aWrongKeystorePasswordIsAConfigError() throws Exception {
        CallConfig c = cfg("https://127.0.0.1:1/");
        c.transport.tls.keystorePath = pki.clientKeystore.toString();
        c.transport.tls.keystorePassword = "nope";
        ConfigException e = assertThrows(ConfigException.class, () -> send(c));
        assertTrue(e.getMessage().contains("Could not open the keystore"), e.getMessage());
    }

    @Test
    void keystoreWithoutPasswordIsRejectedUpFront() {
        CallConfig c = cfg("https://localhost/");
        c.transport.tls.keystorePath = pki.clientKeystore.toString();
        assertThrows(ConfigException.class, c::validate);
    }

    // ---- proxy ----------------------------------------------------------------------------------------------------

    @Test
    void basicProxyAuthenticationWorksForPlainHttp() throws Exception {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"));
             TestProxy proxy = new TestProxy("puser", "ppass")) {
            CallConfig c = cfg(server.url("/soap"));
            c.transport.proxy.type = "http";
            c.transport.proxy.host = "127.0.0.1";
            c.transport.proxy.port = proxy.port();
            c.transport.proxy.user = "puser";
            c.transport.proxy.password = "ppass";
            assertEquals(200, send(c).status);
            assertTrue(proxy.log.stream().anyMatch(l -> l.contains("authorized")), proxy.log.toString());
            assertNull(server.requests.get(0).header("Proxy-Authorization"), "proxy credentials must not reach the origin");
        }
    }

    @Test
    void basicProxyAuthenticationWorksForHttpsTunnels() throws Exception {
        // Java disables Basic auth on CONNECT tunnels by default (jdk.http.auth.tunneling.disabledSchemes=Basic);
        // SoapClient re-enables it because the user supplied the credentials explicitly.
        try (MockServer server = new MockServer(pki.serverContext(), false, r -> Reply.xml("<ok/>"));
             TestProxy proxy = new TestProxy("puser", "ppass")) {
            CallConfig c = cfg(server.url("/soap"));
            c.transport.tls.truststorePath = pki.clientTruststore.toString();
            c.transport.proxy.type = "http";
            c.transport.proxy.host = "127.0.0.1";
            c.transport.proxy.port = proxy.port();
            c.transport.proxy.user = "puser";
            c.transport.proxy.password = "ppass";
            assertEquals(200, send(c).status);
            assertTrue(proxy.log.stream().anyMatch(l -> l.startsWith("CONNECT") && l.endsWith("authorized")), proxy.log.toString());
        }
    }

    @Test
    void wrongProxyCredentialsGiveAClearMessage() throws Exception {
        try (MockServer server = new MockServer(pki.serverContext(), false, r -> Reply.xml("<ok/>"));
             TestProxy proxy = new TestProxy("puser", "ppass")) {
            CallConfig c = cfg(server.url("/soap"));
            c.transport.tls.truststorePath = pki.clientTruststore.toString();
            c.transport.proxy.type = "http";
            c.transport.proxy.host = "127.0.0.1";
            c.transport.proxy.port = proxy.port();
            c.transport.proxy.user = "puser";
            c.transport.proxy.password = "WRONG";
            TransportException e = assertThrows(TransportException.class, () -> send(c));
            assertTrue(e.getMessage().contains("407") && e.getMessage().contains("--proxy-user"), e.getMessage());
        }
    }

    @Test
    void proxyValidation() {
        ProxyConfig p = new ProxyConfig();
        p.host = "h";
        assertThrows(ConfigException.class, p::validate); // port missing
        p.port = 8080;
        assertThrows(ConfigException.class, p::validate); // type missing
        p.type = "ftp";
        assertThrows(ConfigException.class, p::validate); // unsupported
        p.type = "HTTP";
        p.user = "u";
        assertThrows(ConfigException.class, p::validate); // user without password
        p.password = "x";
        p.validate();
    }
}
