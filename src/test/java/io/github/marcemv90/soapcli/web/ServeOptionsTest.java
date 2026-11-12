package io.github.marcemv90.soapcli.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.marcemv90.soapcli.core.ConfigException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ServeOptionsTest {

    private static ServeOptions parse(Map<String, String> env, String... args) {
        return ServeOptions.parse(args, env, "/home/me");
    }

    @Test
    void defaultsAreSafe() {
        ServeOptions o = parse(Collections.emptyMap());
        assertEquals("127.0.0.1", o.host);
        assertEquals(8080, o.port);
        assertTrue(o.isLoopbackOnly());
        assertFalse(o.hasAuth());
        assertTrue(o.allowedHosts.isEmpty());
        assertFalse(o.noInsecureTls);
        assertEquals(25L * 1024 * 1024, o.maxBodyBytes);
        assertEquals(Paths.get("/home/me", ".config", "soap-cli-profiles.json"), o.profileFile);
    }

    @Test
    void attachmentsAreOnlyReadFromTheWorkingDirectoryUnlessToldOtherwise() {
        ServeOptions o = parse(Collections.emptyMap());
        assertEquals(Paths.get("").toAbsolutePath().normalize(), o.attachmentsDir);
        assertFalse(o.attachmentsDirExplicit);

        Map<String, String> env = new HashMap<>();
        env.put("SOAPCLI_ATTACHMENTS_DIR", "/work/../data/files");
        o = parse(env);
        assertEquals(Paths.get("/data/files").toAbsolutePath(), o.attachmentsDir);
        assertTrue(o.attachmentsDirExplicit);

        o = parse(env, "--attachments-dir", "/srv/attach");           // the command line wins
        assertEquals(Paths.get("/srv/attach").toAbsolutePath(), o.attachmentsDir);
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--attachments-dir", " "));
        assertTrue(ServeOptions.usage().contains("--attachments-dir"));
    }

    @Test
    void editingTheConfigurationFilesIsOnlyTheDefaultOnThisMachine() {
        ServeOptions local = parse(Collections.emptyMap());
        assertTrue(local.configEditAllowed());
        assertNull(local.configEditOffReason());

        ServeOptions network = parse(Collections.emptyMap(), "--host", "0.0.0.0");
        assertFalse(network.configEditAllowed());
        assertTrue(network.configEditOffReason().contains("--allow-config-edit"), network.configEditOffReason());

        assertTrue(parse(Collections.emptyMap(), "--host", "0.0.0.0", "--allow-config-edit").configEditAllowed());
        ServeOptions off = parse(Collections.emptyMap(), "--no-config-edit");
        assertFalse(off.configEditAllowed());
        assertTrue(off.configEditOffReason().contains("--no-config-edit"));

        Map<String, String> env = new HashMap<>();
        env.put("SOAPCLI_HOST", "0.0.0.0");
        env.put("SOAPCLI_ALLOW_CONFIG_EDIT", "true");
        assertTrue(parse(env).configEditAllowed());
        env.put("SOAPCLI_ALLOW_CONFIG_EDIT", "false");
        env.put("SOAPCLI_HOST", "127.0.0.1");
        assertFalse(parse(env).configEditAllowed(), "an explicit no beats the local default");
        env.put("SOAPCLI_ALLOW_CONFIG_EDIT", " ");
        assertTrue(parse(env).configEditAllowed(), "blank means not set");
        assertTrue(ServeOptions.usage().contains("--allow-config-edit") && ServeOptions.usage().contains("--placeholders-file"));
    }

    @Test
    void thePlaceholdersFileHasItsOwnLocation() {
        assertEquals(Paths.get("/home/me", ".config", "soap-cli-placeholders.json"), parse(Collections.emptyMap()).placeholdersFile);
        Map<String, String> env = new HashMap<>();
        env.put("SOAPCLI_PLACEHOLDERS_FILE", "/config/placeholders.json");
        assertEquals(Paths.get("/config/placeholders.json"), parse(env).placeholdersFile);
        assertEquals(Paths.get("/x/p.json"), parse(env, "--placeholders-file", "/x/p.json").placeholdersFile);
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--placeholders-file", ""));
    }

    @Test
    void theEnvironmentConfiguresAContainer() {
        Map<String, String> env = new HashMap<>();
        env.put("SOAPCLI_HOST", "0.0.0.0");
        env.put("SOAPCLI_PORT", "9000");
        env.put("SOAPCLI_UI_USER", "alice");
        env.put("SOAPCLI_UI_PASSWORD", "pw");
        env.put("SOAPCLI_ALLOWED_HOSTS", " a.example.com , *.corp.example.com,,");
        env.put("SOAPCLI_MAX_BODY_MB", "5");
        env.put("SOAPCLI_MAX_RESPONSE_MB", "7");
        env.put("SOAPCLI_NO_INSECURE_TLS", "true");
        env.put("SOAPCLI_PROFILE_FILE", "/config/profiles.json");
        ServeOptions o = parse(env);
        assertEquals("0.0.0.0", o.host);
        assertFalse(o.isLoopbackOnly());
        assertEquals(9000, o.port);
        assertTrue(o.hasAuth());
        assertEquals("alice", o.uiUser);
        assertEquals(java.util.List.of("a.example.com", "*.corp.example.com"), o.allowedHosts);
        assertEquals(5L * 1024 * 1024, o.maxBodyBytes);
        assertEquals(7L * 1024 * 1024, o.maxResponseBytes);
        assertTrue(o.noInsecureTls);
        assertEquals(Paths.get("/config/profiles.json"), o.profileFile);
    }

    @Test
    void theCommandLineOverridesTheEnvironment() {
        ServeOptions o = parse(Map.of("SOAPCLI_PORT", "9000", "SOAPCLI_HOST", "0.0.0.0"), "--port=7000", "--host", "localhost",
                "--allow-host", "x", "--allow-host=y", "--profile-file", "/p.json", "--no-insecure-tls", "--max-body-mb", "2");
        assertEquals(7000, o.port);
        assertEquals("localhost", o.host);
        assertEquals(java.util.List.of("x", "y"), o.allowedHosts);
        assertEquals(Paths.get("/p.json"), o.profileFile);
        assertTrue(o.noInsecureTls);
        assertEquals(2L * 1024 * 1024, o.maxBodyBytes);
    }

    @Test
    void thePasswordCanComeFromANamedVariable() {
        ServeOptions o = parse(Map.of("MY_PW", "s3"), "--ui-user", "bob", "--ui-password-env", "MY_PW");
        assertEquals("s3", o.uiPassword);
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--ui-user", "bob", "--ui-password-env", "MISSING"));
    }

    @Test
    void invalidCombinationsAreRejected() {
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--ui-user", "alice")); // no password
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--ui-password", "pw")); // no user
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--port", "abc"));
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--port", "70000"));
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--port"));
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--max-body-mb", "0"));
        assertThrows(ConfigException.class, () -> parse(Collections.emptyMap(), "--bogus"));
        assertThrows(ConfigException.class, () -> parse(Map.of("SOAPCLI_PORT", "x")));
    }

    @Test
    void helpIsRecognisedAndDocumentsEveryOption() {
        assertTrue(parse(Collections.emptyMap(), "--help").help);
        assertTrue(parse(Collections.emptyMap(), "-h").help);
        String usage = ServeOptions.usage();
        for (String flag : new String[]{"--host", "--port", "--profile-file", "--ui-user", "--ui-password", "--ui-password-env",
                "--allow-host", "--no-insecure-tls", "--max-body-mb", "--max-response-mb", "healthcheck", "SOAPCLI_PORT"}) {
            assertTrue(usage.contains(flag), flag);
        }
    }

    @Test
    void loopbackDetection() {
        for (String host : new String[]{"127.0.0.1", "localhost", "LocalHost", "::1", "[::1]", "127.1.2.3"}) {
            ServeOptions o = parse(Collections.emptyMap(), "--host", host);
            assertTrue(o.isLoopbackOnly(), host);
        }
        for (String host : new String[]{"0.0.0.0", "192.168.1.5", "example.com"}) {
            assertFalse(parse(Collections.emptyMap(), "--host", host).isLoopbackOnly(), host);
        }
        assertNull(parse(Collections.emptyMap()).uiUser);
    }
}
