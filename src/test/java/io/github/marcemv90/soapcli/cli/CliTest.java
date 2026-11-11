package io.github.marcemv90.soapcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.marcemv90.soapcli.testsupport.MockServer;
import io.github.marcemv90.soapcli.testsupport.MockServer.Reply;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {

    private static final String ENV11 = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><ping/></s:Body></s:Envelope>";
    private static final String ENV12 = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body><ping/></s:Body></s:Envelope>";

    @TempDir
    Path tmp;

    static final class Result {
        int code;
        String out;
        String err;
        byte[] outBytes;
    }

    private Result run(Map<String, String> env, boolean terminal, InputStream stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Result r = new Result();
        r.code = new Cli(stdin, new PrintStream(out, false, StandardCharsets.UTF_8),
                new PrintStream(err, false, StandardCharsets.UTF_8), env, tmp.toString(), "Linux", terminal).run(args);
        r.out = out.toString(StandardCharsets.UTF_8);
        r.outBytes = out.toByteArray();
        r.err = err.toString(StandardCharsets.UTF_8);
        return r;
    }

    private Result run(String... args) {
        return run(new HashMap<>(), false, new ByteArrayInputStream(new byte[0]), args);
    }

    private Result runWithStdin(String stdin, String... args) {
        return run(new HashMap<>(), false, new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), args);
    }

    private Path file(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        return p;
    }

    private static String[] concat(String[] a, String... b) {
        List<String> all = new ArrayList<>(Arrays.asList(a));
        all.addAll(Arrays.asList(b));
        return all.toArray(new String[0]);
    }

    // ---- the happy path and exit codes ----------------------------------------------------------------------------

    @Test
    void successPrintsFormattedBodyOnStdoutAndStatusOnStderr() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<a><b>1</b></a>"))) {
            Path req = file("r.xml", ENV12);
            Result r = run("--endpoint", server.url("/"), "--request-file", req.toString());
            assertEquals(0, r.code);
            assertEquals("<a>\n  <b>1</b>\n</a>\n", r.out);
            assertTrue(r.err.startsWith("HTTP 200 "), r.err);
            assertFalse(r.out.contains("\u001B"), "no colour when stdout is not a terminal");
        }
    }

    @Test
    void soapFaultsExitWith3NotWithAnHttpStatusThatWrapsAround() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<Fault>boom</Fault>").status(500))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString());
            assertEquals(3, r.code); // the old tool exited with 500 & 0xFF = 244 on Linux
            assertEquals("<Fault>boom</Fault>\n", r.out);
            assertTrue(r.err.contains("HTTP 500"), r.err);
        }
    }

    @Test
    void notFoundAndUnauthorizedAreAlsoExit3() throws IOException {
        for (int status : new int[]{401, 404, 503}) {
            try (MockServer server = new MockServer(r -> Reply.xml("<e/>").status(status))) {
                assertEquals(3, run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString()).code);
            }
        }
    }

    @Test
    void emptyAcceptedResponseIsSuccessWithNoOutput() throws IOException {
        try (MockServer server = new MockServer(r -> new Reply().status(202))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString());
            assertEquals(0, r.code);
            assertEquals("", r.out);
            assertTrue(r.err.contains("HTTP 202"));
        }
    }

    @Test
    void networkFailuresExit2WithoutAStackTrace() throws IOException {
        Result r = run("--endpoint", "http://127.0.0.1:1/", "--request-file", file("r.xml", ENV12).toString());
        assertEquals(2, r.code);
        assertTrue(r.err.startsWith("Error: Could not connect to 127.0.0.1:1"), r.err);
        assertFalse(r.err.contains("\tat "), "stack traces only with --debug");
    }

    @Test
    void debugAddsStackTracesAndTheRequest() throws IOException {
        Result r = run("--endpoint", "http://127.0.0.1:1/", "--request-file", file("r.xml", ENV12).toString(), "--debug");
        assertEquals(2, r.code);
        assertTrue(r.err.contains("==== SOAP-CLI DEBUG: REQUEST ===="), r.err);
        assertTrue(r.err.contains("\tat "), r.err);
    }

    @Test
    void usageErrorsExit1AndPointToHelp() {
        for (String[] args : new String[][]{{"--bogus"}, {"--endpoint"}, {"--timeout", "abc"}, {"--header", "nocolon"},
                {"--arg", "=x"}, {"--theme", "neon"}, {"--soap-version", "9"}, {"--endpoint", "ftp://x"}}) {
            Result r = run(args);
            assertEquals(1, r.code, Arrays.toString(args) + " -> " + r.err);
            assertTrue(r.err.startsWith("Error: "), r.err);
        }
        assertTrue(run("--bogus").err.contains("--help"));
    }

    @Test
    void missingEndpointIsReportedBeforeWaitingForStdin() {
        Result r = runWithStdin(ENV12, "--timeout", "5");
        assertEquals(1, r.code);
        assertTrue(r.err.contains("Missing endpoint"), r.err);
    }

    @Test
    void nonHttpEndpointsAreRejectedCleanly() throws IOException {
        Result r = run("--endpoint", "file:///etc/hostname", "--request-file", file("r.xml", ENV12).toString());
        assertEquals(1, r.code); // used to be a ClassCastException with a stack trace
        assertTrue(r.err.contains("http:// or https://"), r.err);
        assertFalse(r.err.contains("ClassCast"));
    }

    @Test
    void noArgumentsPrintsUsageOnStderrAndExits1() {
        Result r = run();
        assertEquals(1, r.code);
        assertTrue(r.err.contains("Usage:"));
        assertEquals("", r.out);
    }

    @Test
    void helpAndVersionGoToStdoutAndExit0() {
        Result help = run("--help");
        assertEquals(0, help.code);
        assertTrue(help.out.contains("Usage:") && help.out.contains("Exit codes:"));
        assertEquals("", help.err);
        Result version = run("--version");
        assertEquals(0, version.code);
        // println uses the platform's line separator ("\r\n" on Windows), which is right for a console
        assertEquals("SoapCLI dev" + System.lineSeparator(), version.out);
    }

    // ---- request side ---------------------------------------------------------------------------------------------

    @Test
    void headerFlagWorksEndToEnd() throws IOException {
        // Regression: the documented example used to crash with "Already connected".
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString(),
                    "--header", "SOAPAction: \"urn:myAction\"", "--header", "X-Correlation-ID: 12345");
            assertEquals(0, r.code, r.err);
            assertEquals("\"urn:myAction\"", server.requests.get(0).header("SOAPAction"));
            assertEquals("12345", server.requests.get(0).header("X-Correlation-ID"));
        }
    }

    @Test
    void soapVersionAndActionFlags() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path req = file("r.xml", ENV11);
            assertEquals(0, run("--endpoint", server.url("/"), "--request-file", req.toString(), "--soap-action", "urn:x").code);
            assertEquals("text/xml; charset=utf-8", server.requests.get(0).header("Content-Type"));
            assertEquals("\"urn:x\"", server.requests.get(0).header("SOAPAction"));

            run("--endpoint", server.url("/"), "--request-file", req.toString(), "--soap-version", "1.2", "--soap-action", "urn:y");
            assertEquals("application/soap+xml; charset=utf-8; action=\"urn:y\"", server.requests.get(1).header("Content-Type"));
        }
    }

    @Test
    void stdinFromASlowProducerIsWaitedFor() throws Exception {
        // Regression: System.in.available() == 0 made a slow `producer | SoapCLI` fail with "stdin is empty".
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            PipedOutputStream producer = new PipedOutputStream();
            PipedInputStream stdin = new PipedInputStream(producer);
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(600);
                    producer.write(ENV12.getBytes(StandardCharsets.UTF_8));
                    producer.close();
                } catch (Exception ignored) {
                    // test will fail on its own
                }
            });
            t.start();
            Result r = run(new HashMap<>(), false, stdin, "--endpoint", server.url("/"));
            t.join();
            assertEquals(0, r.code, r.err);
            assertEquals(ENV12, server.requests.get(0).bodyText());
        }
    }

    @Test
    void anEmptyRequestIsAnError() {
        Result r = runWithStdin("   \n", "--endpoint", "http://127.0.0.1:1/");
        assertEquals(1, r.code);
        assertTrue(r.err.contains("The request is empty"), r.err);
    }

    @Test
    void dashMeansStdin() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            assertEquals(0, runWithStdin(ENV12, "--endpoint", server.url("/"), "--request-file", "-").code);
        }
    }

    @Test
    void placeholdersEmptyOverridesEscapingAndTypoWarnings() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path req = file("r.xml", ENV12.replace("<ping/>", "<a>___A:x___</a><b>___B:y___</b><c>___C:z___</c>"));
            Result r = run("--endpoint", server.url("/"), "--request-file", req.toString(),
                    "--arg", "A=", "--arg-text", "B=Tom & Jerry <3", "--arg", "TYPO=1");
            assertEquals(0, r.code, r.err);
            String body = server.requests.get(0).bodyText();
            assertTrue(body.contains("<a></a>"), body);
            assertTrue(body.contains("<b>Tom &amp; Jerry &lt;3</b>"), body);
            assertTrue(body.contains("<c>z</c>"), body);
            assertTrue(r.err.contains("Warning: Argument 'TYPO'"), r.err);
        }
    }

    @Test
    void aUtf8BomInTheRequestFileIsHarmlessEvenWithInlineAttachments() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path bom = tmp.resolve("bom.xml");
            Files.write(bom, ("﻿" + ENV12.replace("<ping/>", "<x>cid:f</x>")).getBytes(StandardCharsets.UTF_8));
            Path att = file("a.bin", "hello");
            Result r = run("--endpoint", server.url("/"), "--request-file", bom.toString(), "--attachment", "f=" + att);
            assertEquals(0, r.code, r.err); // used to fail: "Content is not allowed in prolog"
            assertTrue(server.requests.get(0).bodyText().contains("<x>aGVsbG8=</x>"));
            assertFalse(server.requests.get(0).bodyText().startsWith("﻿"));
        }
    }

    @Test
    void aLatin1RequestIsSentAsLatin1() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path req = tmp.resolve("l1.xml");
            Files.write(req, ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + ENV12.replace("<ping/>", "<n>ñ</n>"))
                    .getBytes(StandardCharsets.ISO_8859_1));
            assertEquals(0, run("--endpoint", server.url("/"), "--request-file", req.toString()).code);
            assertEquals((int) Files.size(req), server.requests.get(0).body.length, "same bytes, no re-encoding");
            assertTrue(server.requests.get(0).header("Content-Type").contains("iso-8859-1"));
        }
    }

    @Test
    void basicAuthFromEnvironmentLosesToTheFlag() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Map<String, String> env = new HashMap<>();
            env.put("CREDS", "env:pw");
            Path req = file("r.xml", ENV12);
            run(env, false, new ByteArrayInputStream(new byte[0]), "--endpoint", server.url("/"), "--request-file",
                    req.toString(), "--auth-basic-env", "CREDS");
            assertEquals("Basic ZW52OnB3", server.requests.get(0).header("Authorization"));

            run(env, false, new ByteArrayInputStream(new byte[0]), "--endpoint", server.url("/"), "--request-file",
                    req.toString(), "--auth-basic", "flag:pw", "--auth-basic-env", "CREDS");
            assertEquals("Basic ZmxhZzpwdw==", server.requests.get(1).header("Authorization"));

            Result missing = run(env, false, new ByteArrayInputStream(new byte[0]), "--endpoint", server.url("/"),
                    "--request-file", req.toString(), "--auth-bearer-env", "NOPE");
            assertEquals(1, missing.code);
            assertTrue(missing.err.contains("NOPE"));
        }
    }

    @Test
    void mtomEndToEnd() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path req = file("r.xml", ENV12.replace("<ping/>", "<v>cid:message10</v>"));
            Path att = file("a.bin", "DATA");
            Result r = run("--endpoint", server.url("/"), "--request-file", req.toString(),
                    "--attachment", "message1=" + att, "--attachment", "message10=" + att, "--mtom");
            assertEquals(0, r.code, r.err);
            String body = new String(server.requests.get(0).body, StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("<v><inc:Include href=\"cid:message10\""), body);
            assertFalse(body.contains("/>0<"));
            assertTrue(r.err.contains("Warning: Attachment 'message1' is not referenced"), r.err);
        }
    }

    // ---- dry run / secrets ----------------------------------------------------------------------------------------

    @Test
    void dryRunSendsNothingAndMasksCredentials() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Path req = file("r.xml", ENV12);
            Result r = run("--endpoint", server.url("/"), "--request-file", req.toString(), "--dry-run",
                    "--auth-basic", "alice:S3cr3t!", "--proxy-type", "http", "--proxy-host", "h", "--proxy-port", "1",
                    "--proxy-user", "u", "--proxy-password", "PROXYPW");
            assertEquals(0, r.code, r.err);
            assertTrue(server.requests.isEmpty());
            assertTrue(r.out.contains("Authorization: Basic ****"), r.out);
            assertFalse(r.out.contains("YWxpY2U6"), "the Base64 of the credentials must not be shown");
            assertFalse(r.out.contains("PROXYPW"));
            assertTrue(r.out.contains("Content-Length: " + ENV12.length()));
            assertTrue(r.out.contains("<s:Envelope"));

            Result shown = run("--endpoint", server.url("/"), "--request-file", req.toString(), "--dry-run",
                    "--auth-basic", "alice:S3cr3t!", "--show-secrets");
            assertTrue(shown.out.contains("Authorization: Basic YWxpY2U6UzNjcjN0IQ=="), shown.out);
        }
    }

    @Test
    void dryRunWithInlineAttachmentsDoesNotDumpTheBase64() throws IOException {
        Path req = file("r.xml", ENV12.replace("<ping/>", "<x>cid:f</x>"));
        Path att = file("a.bin", "hello");
        Result r = run("--endpoint", "http://127.0.0.1:1/", "--request-file", req.toString(), "--attachment", "f=" + att,
                "--dry-run");
        assertTrue(r.out.contains("[base64 of a.bin, 5 bytes]"), r.out);
        assertFalse(r.out.contains("aGVsbG8"));
    }

    @Test
    void debugMasksResponseCookiesToo() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>").header("Set-Cookie", "sid=topsecret"))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString(), "-i");
            // the JDK test server normalises header-name case, so compare case-insensitively
            assertTrue(r.err.toLowerCase().contains("set-cookie: ****"), r.err);
            assertFalse(r.err.contains("topsecret"));
            assertTrue(r.err.toLowerCase().contains("content-type: text/xml"));
        }
    }

    // ---- output ---------------------------------------------------------------------------------------------------

    @Test
    void colourIsOnlyAutomaticOnATerminal() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<a x=\"1\"><b/></a>"))) {
            String[] base = {"--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString()};
            Map<String, String> none = new HashMap<>();

            assertFalse(run(none, false, new ByteArrayInputStream(new byte[0]), base).out.contains("\u001B"));
            assertTrue(run(none, true, new ByteArrayInputStream(new byte[0]), base).out.contains("\u001B["));
            assertFalse(run(none, true, new ByteArrayInputStream(new byte[0]), concat(base, "--no-color")).out.contains("\u001B"));
            assertTrue(run(none, false, new ByteArrayInputStream(new byte[0]), concat(base, "--color")).out.contains("\u001B["));

            Map<String, String> noColor = Collections.singletonMap("NO_COLOR", "1");
            assertFalse(run(noColor, true, new ByteArrayInputStream(new byte[0]), base).out.contains("\u001B"));
            Map<String, String> force = Collections.singletonMap("FORCE_COLOR", "1");
            assertTrue(run(force, false, new ByteArrayInputStream(new byte[0]), base).out.contains("\u001B["));
        }
    }

    @Test
    void rawPrintsTheBodyUntouched() throws IOException {
        String body = "<a><b>1</b></a>";
        try (MockServer server = new MockServer(r -> Reply.xml(body))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString(), "--raw");
            assertEquals(body, r.out);
        }
    }

    @Test
    void outputFileReceivesTheBodyAndNothingGoesToStdout() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<a><b>1</b></a>"))) {
            Path target = tmp.resolve("response.xml");
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString(), "-o", target.toString());
            assertEquals(0, r.code, r.err);
            assertEquals("", r.out);
            assertEquals("<a>\n  <b>1</b>\n</a>\n", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
            assertTrue(r.err.contains("saved to"));
        }
    }

    @Test
    void quietHidesTheStatusLine() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<ok/>"))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString(), "-q");
            assertEquals("", r.err);
        }
    }

    @Test
    void responsesInOtherCharsetsAreShownCorrectly() throws IOException {
        byte[] latin1 = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>ñandú á é í</a>".getBytes(StandardCharsets.ISO_8859_1);
        try (MockServer server = new MockServer(r -> new Reply().bytes(latin1, "text/xml; charset=ISO-8859-1"))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString());
            assertTrue(r.out.contains("<a>ñandú á é í</a>"), r.out); // used to print U+FFFD replacement characters
        }
    }

    @Test
    void aMaliciousDoctypeInTheResponseIsNeverResolved() throws IOException {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]><a>&xxe;</a>";
        try (MockServer server = new MockServer(r -> Reply.xml(xxe))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString());
            assertTrue(r.out.contains("<a>&xxe;</a>"), r.out);
            String hostname = Files.exists(Path.of("/etc/hostname")) ? Files.readString(Path.of("/etc/hostname")).trim() : "";
            if (!hostname.isEmpty()) {
                assertFalse(r.out.replace("file:///etc/hostname", "").contains(hostname), "local file contents leaked: " + r.out);
            }
        }
    }

    @Test
    void multipartResponsesAreNotMangled() throws IOException {
        byte[] multipart = ("--b\r\nContent-Type: text/xml\r\n\r\n<a/>\r\n--b--\r\n").getBytes(StandardCharsets.ISO_8859_1);
        try (MockServer server = new MockServer(r -> new Reply().bytes(multipart, "multipart/related; boundary=b; type=\"application/xop+xml\""))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString());
            assertEquals(new String(multipart, StandardCharsets.ISO_8859_1), new String(r.outBytes, StandardCharsets.ISO_8859_1));
        }
    }

    // ---- redirects ------------------------------------------------------------------------------------------------

    @Test
    void anUnfollowedRedirectIsExit3WithAHint() throws IOException {
        try (MockServer target = new MockServer(r -> Reply.xml("<landed/>"));
             MockServer origin = new MockServer(r -> new Reply().status(302).header("Location", target.url("/x")))) {
            Result r = run("--endpoint", origin.url("/"), "--request-file", file("r.xml", ENV12).toString());
            assertEquals(3, r.code);
            assertTrue(r.err.contains("--follow-redirects"), r.err);
            assertTrue(target.requests.isEmpty());

            Result followed = run("--endpoint", origin.url("/"), "--request-file", file("r.xml", ENV12).toString(), "--follow-redirects");
            assertEquals(0, followed.code, followed.err);
            assertEquals("POST", target.requests.get(0).method);
            assertEquals("<landed/>\n", followed.out);
        }
    }

    @Test
    void timeoutFlagIsHonoured() throws IOException {
        try (MockServer server = new MockServer(r -> Reply.xml("<slow/>").delay(2500))) {
            Result r = run("--endpoint", server.url("/"), "--request-file", file("r.xml", ENV12).toString(), "--timeout", "1");
            assertEquals(2, r.code);
            assertTrue(r.err.contains("did not answer within 1 s"), r.err);
        }
    }

    @Test
    void untrustedTlsExit2WithTheFixInTheMessage() throws Exception {
        io.github.marcemv90.soapcli.testsupport.TestPki pki = new io.github.marcemv90.soapcli.testsupport.TestPki(Files.createTempDirectory(tmp, "pki"));
        try (MockServer server = new MockServer(pki.serverContext(), false, r -> Reply.xml("<ok/>"))) {
            Path req = file("r.xml", ENV12);
            Result untrusted = run("--endpoint", server.url("/"), "--request-file", req.toString());
            assertEquals(2, untrusted.code);
            assertTrue(untrusted.err.contains("--truststore") && untrusted.err.contains("--insecure"), untrusted.err);

            Result trusted = run("--endpoint", server.url("/"), "--request-file", req.toString(),
                    "--truststore", pki.clientTruststore.toString(), "--truststore-password-env", "TS");
            assertEquals(1, trusted.code); // TS is not set: clear usage error, nothing sent
            assertTrue(trusted.err.contains("'TS'"), trusted.err);

            Map<String, String> env = Collections.singletonMap("TS", "changeit");
            Result ok = run(env, false, new ByteArrayInputStream(new byte[0]), "--endpoint", server.url("/"),
                    "--request-file", req.toString(), "--truststore", pki.clientTruststore.toString(),
                    "--truststore-password-env", "TS");
            assertEquals(0, ok.code, ok.err);

            Result insecure = run("--endpoint", server.url("/"), "--request-file", req.toString(), "--insecure");
            assertEquals(0, insecure.code, insecure.err);
            assertTrue(insecure.err.contains("Warning: TLS certificate and host name verification are DISABLED"), insecure.err);
        }
    }
}
