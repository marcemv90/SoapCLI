package io.github.marcemv90.soapcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.marcemv90.soapcli.testsupport.MockServer;
import io.github.marcemv90.soapcli.testsupport.MockServer.Reply;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliProfilesTest {

    private static final String ENV12 = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body><ping>___ID:1___</ping></s:Body></s:Envelope>";

    @TempDir
    Path tmp;

    private String out;
    private String err;

    private int run(Map<String, String> env, String... args) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        ByteArrayOutputStream e = new ByteArrayOutputStream();
        int code = new Cli(new ByteArrayInputStream(new byte[0]), new PrintStream(o, false, StandardCharsets.UTF_8),
                new PrintStream(e, false, StandardCharsets.UTF_8), env, tmp.toString(), "Linux", false).run(args);
        out = o.toString(StandardCharsets.UTF_8);
        err = e.toString(StandardCharsets.UTF_8);
        return code;
    }

    private Path profiles(String json) throws IOException {
        Path f = tmp.resolve("profiles.json");
        Files.write(f, json.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private Path request() throws IOException {
        Path f = tmp.resolve("req.xml");
        Files.write(f, ENV12.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    void aProfileSuppliesEndpointHeadersAuthAndPlaceholders() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path pf = profiles("{\"profiles\":{\"acc\":{\"endpoint\":\"" + server.url("/soap") + "\",\"requestFile\":\""
                    + request().toString().replace("\\", "\\\\") + "\",\"soapVersion\":\"1.1\",\"soapAction\":\"urn:acc\","
                    + "\"headers\":{\"X-Env\":\"acceptance\",\"X-Keep\":\"yes\"},\"authBasic\":\"${CREDS}\",\"args\":{\"ID\":\"77\"}}}}");
            Map<String, String> env = Collections.singletonMap("CREDS", "alice:pw");
            assertEquals(0, run(env, "--profile-file", pf.toString(), "--profile", "acc"), err);

            MockServer.Received r = server.requests.get(0);
            assertEquals("/soap", r.path);
            assertEquals("acceptance", r.header("X-Env"));
            assertEquals("\"urn:acc\"", r.header("SOAPAction"));
            assertEquals("text/xml; charset=utf-8", r.header("Content-Type"));
            assertTrue(r.header("Authorization").startsWith("Basic "));
            assertTrue(r.bodyText().contains("<ping>77</ping>"));
        }
    }

    @Test
    void commandLineOverridesTheProfile() throws IOException {
        try (MockServer wrong = new MockServer(r -> Reply.xml("<wrong/>"));
             MockServer right = new MockServer(r -> Reply.xml("<right/>"))) {
            Path pf = profiles("{\"profiles\":{\"acc\":{\"endpoint\":\"" + wrong.url("/") + "\",\"requestFile\":\""
                    + request().toString().replace("\\", "\\\\") + "\",\"headers\":{\"X-Env\":\"profile\",\"X-Keep\":\"yes\"},"
                    + "\"args\":{\"ID\":\"1\"},\"soapVersion\":\"1.1\"}}}");
            assertEquals(0, run(new HashMap<>(), "--profile-file", pf.toString(), "--profile", "acc", "--endpoint", right.url("/"),
                    "--header", "X-Env: cli", "--arg", "ID=2", "--soap-version", "1.2"), err);
            assertTrue(wrong.requests.isEmpty());
            MockServer.Received r = right.requests.get(0);
            assertEquals("cli", r.header("X-Env"));
            assertEquals("yes", r.header("X-Keep"), "profile headers are merged, not replaced");
            assertTrue(r.bodyText().contains("<ping>2</ping>"));
            assertTrue(r.header("Content-Type").startsWith("application/soap+xml"));
        }
    }

    @Test
    void listAndShowProfilesMaskSecrets() throws IOException {
        Path pf = profiles("{\"profiles\":{\"base\":{\"theme\":\"nord\"},\"acc\":{\"extends\":\"base\",\"endpoint\":\"http://x\","
                + "\"authBasic\":\"alice:hunter2\",\"keystore\":{\"path\":\"k.p12\",\"password\":\"topsecret\"}}}}");
        assertEquals(0, run(new HashMap<>(), "--profile-file", pf.toString(), "--list-profiles"));
        assertTrue(out.contains("- base") && out.contains("- acc"), out);

        assertEquals(0, run(new HashMap<>(), "--profile-file", pf.toString(), "--list-profiles-detailed"));
        assertTrue(out.contains("- acc:") && out.contains("extends: base") && out.contains("password: **** (set)"), out);
        assertFalse(out.contains("hunter2") || out.contains("topsecret"), out);

        assertEquals(0, run(new HashMap<>(), "--profile-file", pf.toString(), "--show-profile", "acc"));
        assertTrue(out.contains("\"endpoint\" : \"http://x\"") && out.contains("\"authBasic\" : \"****\""), out);
        assertFalse(out.contains("hunter2") || out.contains("topsecret"), out);
    }

    @Test
    void theProfileFileCanComeFromTheEnvironment() throws IOException {
        Path pf = profiles("{\"profiles\":{\"only\":{}}}");
        Map<String, String> env = Collections.singletonMap("SOAPCLI_PROFILE_FILE", pf.toString());
        assertEquals(0, run(env, "--list-profiles"));
        assertTrue(out.contains("- only"));
    }

    @Test
    void profileProblemsAreUsageErrors() throws IOException {
        Path pf = profiles("{\"profiles\":{\"a\":{\"authBasic\":\"${NOT_SET}\"},\"b\":{\"theme\":\"neon\"}}}");
        assertEquals(1, run(new HashMap<>(), "--profile-file", pf.toString(), "--profile", "a"));
        assertTrue(err.contains("NOT_SET"), err);
        assertEquals(1, run(new HashMap<>(), "--profile-file", pf.toString(), "--profile", "b", "--endpoint", "http://x"));
        assertTrue(err.contains("neon"), err);
        assertEquals(1, run(new HashMap<>(), "--profile-file", pf.toString(), "--profile", "ghost"));
        assertTrue(err.contains("ghost") && err.contains("Available: a, b"), err);
        assertEquals(1, run(new HashMap<>(), "--profile-file", tmp.resolve("nope.json").toString(), "--list-profiles"));
        assertTrue(err.contains("not found"), err);
    }

    @Test
    void unknownProfileKeysAreReportedOnStderr() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path pf = profiles("{\"profiles\":{\"a\":{\"endpoint\":\"" + server.url("/") + "\",\"requestFile\":\""
                    + request().toString().replace("\\", "\\\\") + "\",\"timeot\":5}}}");
            assertEquals(0, run(new HashMap<>(), "--profile-file", pf.toString(), "--profile", "a"), err);
            assertTrue(err.contains("Warning: Profile 'a': unknown setting 'timeot'"), err);
        }
    }

    @Test
    void profileTimeoutIsApplied() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<slow/>").delay(2500))) {
            Path pf = profiles("{\"profiles\":{\"a\":{\"endpoint\":\"" + server.url("/") + "\",\"requestFile\":\""
                    + request().toString().replace("\\", "\\\\") + "\",\"timeout\":1}}}");
            assertEquals(2, run(new HashMap<>(), "--profile-file", pf.toString(), "--profile", "a"));
            assertTrue(err.contains("within 1 s"), err);
        }
    }
}
