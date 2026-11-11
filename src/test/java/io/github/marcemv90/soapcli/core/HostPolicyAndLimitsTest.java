package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.marcemv90.soapcli.testsupport.MockServer;
import io.github.marcemv90.soapcli.testsupport.MockServer.Reply;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class HostPolicyAndLimitsTest {

    private final SoapClient client = new SoapClient();

    private SoapResponse send(CallConfig c) {
        return client.send(RequestBuilder.build(c, RequestBuilderTest.ENV12, StandardCharsets.UTF_8, "ua"), c.transport);
    }

    private static CallConfig cfg(String url) {
        CallConfig c = new CallConfig();
        c.endpoint = url;
        return c;
    }

    // ---- patterns -------------------------------------------------------------------------------------------------

    @Test
    void exactNamesAreCaseInsensitiveAndIgnoreThePort() {
        HostPolicy p = new HostPolicy(Arrays.asList("API.example.com", "10.0.0.5"));
        assertTrue(p.allows("api.EXAMPLE.com"));
        assertTrue(p.allows("10.0.0.5"));
        assertFalse(p.allows("api.example.com.evil.net"));
        assertFalse(p.allows("evil-api.example.com"));
        assertFalse(p.allows("10.0.0.50"));
    }

    @Test
    void wildcardsStandForAnyRun() {
        HostPolicy p = new HostPolicy(Arrays.asList("*.corp.example.com", "10.1.*"));
        assertTrue(p.allows("a.corp.example.com"));
        assertTrue(p.allows("a.b.corp.example.com"));
        assertFalse(p.allows("corp.example.com"), "*.x does not match x itself");
        assertTrue(p.allows("10.1.2.3"));
        assertFalse(p.allows("10.2.2.3"));
        assertFalse(p.allows("evilcorp.example.com"));
    }

    @Test
    void regexCharactersInPatternsAreLiteral() {
        HostPolicy p = new HostPolicy(Collections.singletonList("a.b"));
        assertTrue(p.allows("a.b"));
        assertFalse(p.allows("axb"));
        assertFalse(new HostPolicy(Collections.singletonList("(x")).allows("x"));
    }

    @Test
    void anEmptyPolicyIsOpenAndBlanksAreIgnored() {
        assertTrue(new HostPolicy(Collections.emptyList()).isOpen());
        assertTrue(new HostPolicy(Arrays.asList(" ", "")).allows("anything"));
        assertFalse(new HostPolicy(Collections.singletonList("x")).isOpen());
        assertFalse(new HostPolicy(Collections.singletonList("x")).allows(null));
    }

    @Test
    void checkExplainsWhatIsAllowed() {
        HostNotAllowedException e = assertThrows(HostNotAllowedException.class,
                () -> new HostPolicy(Arrays.asList("a.example.com", "*.b.example.com")).check("evil.net"));
        assertTrue(e.getMessage().contains("evil.net") && e.getMessage().contains("a.example.com"), e.getMessage());
    }

    // ---- in the client --------------------------------------------------------------------------------------------

    @Test
    void aForbiddenHostIsRefusedBeforeAnythingIsSent() {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.hostPolicy = new HostPolicy(Collections.singletonList("only.example.com"));
            assertThrows(HostNotAllowedException.class, () -> send(c));
            assertTrue(server.requests.isEmpty());
        }
    }

    @Test
    void anAllowedHostPasses() {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.hostPolicy = new HostPolicy(Collections.singletonList("127.0.0.1"));
            assertEquals(200, send(c).status);
        }
    }

    @Test
    void redirectsToForbiddenHostsAreNotFollowed() {
        try (MockServer target = new MockServer(r -> Reply.xml("<landed/>"));
             MockServer origin = new MockServer(r -> new Reply().status(302)
                     .header("Location", "http://localhost:" + target.port() + "/inside"))) {
            CallConfig c = cfg(origin.url("/"));
            c.transport.followRedirects = true;
            c.transport.hostPolicy = new HostPolicy(Collections.singletonList("127.0.0.1")); // "localhost" is not listed
            assertThrows(HostNotAllowedException.class, () -> send(c));
            assertEquals(1, origin.requests.size());
            assertTrue(target.requests.isEmpty(), "the redirect target must never be contacted");
        }
    }

    // ---- response size --------------------------------------------------------------------------------------------

    @Test
    void responsesOverTheLimitAreRefused() {
        byte[] body = new byte[1000];
        Arrays.fill(body, (byte) 'x');
        try (MockServer server = new MockServer(r -> new Reply().bytes(body, "text/plain"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.maxResponseBytes = 100;
            TransportException e = assertThrows(TransportException.class, () -> send(c));
            assertTrue(e.getMessage().contains("larger than the limit of 100 bytes"), e.getMessage());
        }
    }

    @Test
    void aResponseExactlyAtTheLimitIsAccepted() {
        byte[] body = new byte[100];
        Arrays.fill(body, (byte) 'x');
        try (MockServer server = new MockServer(r -> new Reply().bytes(body, "text/plain"))) {
            CallConfig c = cfg(server.url("/"));
            c.transport.maxResponseBytes = 100;
            assertEquals(100, send(c).body.length);
            c.transport.maxResponseBytes = 99;
            assertThrows(TransportException.class, () -> send(c));
        }
    }

    @Test
    void noLimitMeansNoLimit() {
        try (MockServer server = new MockServer(r -> new Reply().bytes(new byte[200_000], "text/plain"))) {
            assertEquals(200_000, send(cfg(server.url("/"))).body.length);
        }
    }

    @Test
    void aNegativeLimitIsAConfigError() {
        CallConfig c = cfg("http://localhost/");
        c.transport.maxResponseBytes = -1;
        assertThrows(ConfigException.class, c::validate);
    }
}
