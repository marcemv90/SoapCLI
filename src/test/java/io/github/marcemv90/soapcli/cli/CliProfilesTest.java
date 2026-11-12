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

    // ---- workspaces ---------------------------------------------------------------------------------------------------

    private Path workspaces(String name, String profilesJson) throws IOException {
        Path folder = Files.createDirectories(tmp.resolve("workspaces").resolve(name));
        Files.write(folder.resolve("profiles.json"), profilesJson.getBytes(StandardCharsets.UTF_8));
        return folder;
    }

    @Test
    void aWorkspaceSuppliesItsProfilesItsSecretsAndTheFolderRelativePathsStartIn() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path ws = workspaces("billing", "{\"profiles\":{\"acc\":{\"endpoint\":\"" + server.url("/soap") + "\","
                    + "\"requestFile\":\"requests/req.xml\",\"authBasic\":\"${CREDS}\",\"args\":{\"ID\":\"5\"}}}}");
            Files.createDirectories(ws.resolve("requests"));
            Files.write(ws.resolve("requests").resolve("req.xml"), ENV12.getBytes(StandardCharsets.UTF_8));
            Files.write(ws.resolve("secrets.env"), "CREDS=alice:from-secrets-env\n".getBytes(StandardCharsets.UTF_8));

            // the working directory is somewhere else entirely, so a relative path can only mean "inside the workspace"
            assertEquals(0, run(Collections.emptyMap(), "--workspaces-dir", tmp.resolve("workspaces").toString(),
                    "--workspace", "billing", "--profile", "acc"), err);
            assertEquals("Basic YWxpY2U6ZnJvbS1zZWNyZXRzLWVudg==", server.requests.get(0).header("Authorization"));
            assertTrue(server.requests.get(0).bodyText().contains("<ping>5</ping>"), server.requests.get(0).bodyText());

            // the folder can come from the environment, and the listing commands read the same file
            Map<String, String> env = Collections.singletonMap("SOAPCLI_WORKSPACES_DIR", tmp.resolve("workspaces").toString());
            assertEquals(0, run(env, "--workspace", "billing", "--list-profiles"), err);
            assertTrue(out.contains(tmp.resolve("workspaces").resolve("billing").resolve("profiles.json").toString()) && out.contains("- acc"), out);
            assertEquals(0, run(env, "--workspace", "billing", "--show-profile", "acc"), err);
            assertTrue(out.contains("requests/req.xml") && !out.contains("from-secrets-env"), out);
        }
    }

    @Test
    void theCommandLineAndTheProcessEnvironmentStillWorkNextToAWorkspace() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            workspaces("billing", "{\"profiles\":{\"acc\":{\"endpoint\":\"" + server.url("/") + "\",\"authBasic\":\"${CREDS}\"}}}");
            Map<String, String> env = new HashMap<>();
            env.put("CREDS", "bob:from-the-process");
            env.put("SOAPCLI_WORKSPACES_DIR", tmp.resolve("workspaces").toString());
            assertEquals(0, run(env, "--workspace", "billing", "--profile", "acc", "--request-file", request().toString()), err);
            assertEquals("Basic Ym9iOmZyb20tdGhlLXByb2Nlc3M=", server.requests.get(0).header("Authorization"));
        }
    }

    @Test
    void aWorkspaceThatCannotBeUsedIsReportedBeforeAnythingIsSent() throws IOException {
        workspaces("billing", "{\"profiles\":{}}");
        String dir = tmp.resolve("workspaces").toString();
        Path file = profiles("{\"profiles\":{}}");

        assertEquals(1, run(Collections.emptyMap(), "--workspace", "billing", "--list-profiles"));
        assertTrue(err.contains("--workspaces-dir or SOAPCLI_WORKSPACES_DIR"), err);
        assertEquals(1, run(Collections.emptyMap(), "--workspaces-dir", dir, "--list-profiles"));
        assertTrue(err.contains("only makes sense together with --workspace"), err);
        assertEquals(1, run(Collections.emptyMap(), "--workspaces-dir", dir, "--workspace", "nope", "--list-profiles"));
        assertTrue(err.contains("There is no workspace 'nope'"), err);
        for (String bad : new String[]{"../billing", "a/b", ".hidden", ""}) {
            assertEquals(1, run(Collections.emptyMap(), "--workspaces-dir", dir, "--workspace", bad, "--list-profiles"), bad);
            assertTrue(err.contains("is not a workspace name"), bad + " -> " + err);
        }
        assertEquals(1, run(Collections.emptyMap(), "--workspaces-dir", tmp.resolve("missing").toString(), "--workspace", "billing", "--list-profiles"));
        assertTrue(err.contains("does not exist"), err);
        assertEquals(1, run(Collections.emptyMap(), "--workspaces-dir", dir, "--workspace", "billing", "--profile-file", file.toString(), "--list-profiles"));
        assertTrue(err.contains("--profile-file cannot be used with --workspace"), err);
        assertTrue(Options.usage().contains("--workspace <name>") && Options.usage().contains("--workspaces-dir <dir>"));
    }
}
