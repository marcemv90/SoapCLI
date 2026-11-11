package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfilesTest {

    @TempDir
    Path dir;

    private Profiles profiles(String json) throws IOException {
        Path f = dir.resolve("p.json");
        Files.write(f, json.getBytes("UTF-8"));
        return Profiles.load(f);
    }

    @Test
    void listsNamesAndAcceptsCommentsAndTrailingCommas() throws IOException {
        Profiles p = profiles("{ // my profiles\n \"profiles\": { \"acc\": {\"endpoint\": \"http://a\",}, /* x */ \"prod\": {} } }");
        assertEquals(Arrays.asList("acc", "prod"), p.names());
    }

    @Test
    void invalidFilesAreReportedClearly() throws IOException {
        assertThrows(ConfigException.class, () -> Profiles.load(dir.resolve("missing.json")));
        assertThrows(ConfigException.class, () -> profiles("{\"nope\": {}}"));
        assertThrows(ConfigException.class, () -> profiles("{ not json"));
        ConfigException e = assertThrows(ConfigException.class, () -> profiles("{\"profiles\":{\"a\":{}}}").resolve("zzz", Collections.emptyMap()));
        assertTrue(e.getMessage().contains("Available: a"), e.getMessage());
    }

    @Test
    void extendsMergesDeeplyAndChildWins() throws IOException {
        Profiles p = profiles("{\"profiles\":{"
                + "\"base\":{\"proxy\":{\"type\":\"http\",\"host\":\"px\",\"port\":3128},\"headers\":{\"X-A\":\"1\",\"X-B\":\"2\"},\"theme\":\"nord\"},"
                + "\"acc\":{\"extends\":\"base\",\"endpoint\":\"http://acc\",\"proxy\":{\"port\":8080},\"headers\":{\"X-B\":\"override\"}}}}");
        Profile acc = p.resolve("acc", Collections.emptyMap());
        assertEquals("http://acc", acc.endpoint);
        assertEquals("px", acc.proxyHost);
        assertEquals(Integer.valueOf(8080), acc.proxyPort);
        assertEquals("nord", acc.theme);
        assertEquals("1", acc.headers.get("X-A"));
        assertEquals("override", acc.headers.get("X-B"));
    }

    @Test
    void nullUnsetsAnInheritedValue() throws IOException {
        Profiles p = profiles("{\"profiles\":{\"base\":{\"proxy\":{\"type\":\"http\",\"host\":\"px\",\"port\":1},\"theme\":\"nord\"},"
                + "\"direct\":{\"extends\":\"base\",\"proxy\":null,\"theme\":null}}}");
        Profile direct = p.resolve("direct", Collections.emptyMap());
        assertNull(direct.proxyHost);
        assertNull(direct.theme);
    }

    @Test
    void multiLevelChainsAndLoopsAndUnknownParents() throws IOException {
        Profiles ok = profiles("{\"profiles\":{\"a\":{\"theme\":\"nord\"},\"b\":{\"extends\":\"a\"},\"c\":{\"extends\":\"b\",\"endpoint\":\"http://c\"}}}");
        assertEquals("nord", ok.resolve("c", Collections.emptyMap()).theme);

        Profiles loop = profiles("{\"profiles\":{\"a\":{\"extends\":\"b\"},\"b\":{\"extends\":\"a\"}}}");
        ConfigException e = assertThrows(ConfigException.class, () -> loop.resolve("a", Collections.emptyMap()));
        assertTrue(e.getMessage().contains("a -> b -> a"), e.getMessage());

        Profiles orphan = profiles("{\"profiles\":{\"a\":{\"extends\":\"ghost\"}}}");
        assertTrue(assertThrows(ConfigException.class, () -> orphan.resolve("a", Collections.emptyMap())).getMessage().contains("ghost"));
    }

    @Test
    void environmentVariablesAreExpandedWithOptionalDefaults() throws IOException {
        Profiles p = profiles("{\"profiles\":{\"a\":{\"endpoint\":\"https://${HOST}/soap\",\"authBasic\":\"${USER_PW}\","
                + "\"keystore\":{\"path\":\"k.p12\",\"password\":\"${KS_PW}\"},\"headers\":{\"X-Env\":\"${ENVNAME:-dev}\"}}}}");
        Map<String, String> env = new HashMap<>();
        env.put("HOST", "example.com");
        env.put("USER_PW", "u:p");
        env.put("KS_PW", "s3cret");
        Profile a = p.resolve("a", env);
        assertEquals("https://example.com/soap", a.endpoint);
        assertEquals("u:p", a.authBasic);
        assertEquals("s3cret", a.keystorePassword);
        assertEquals("dev", a.headers.get("X-Env"));

        env.remove("KS_PW");
        ConfigException e = assertThrows(ConfigException.class, () -> p.resolve("a", env));
        assertTrue(e.getMessage().contains("KS_PW") && e.getMessage().contains("keystore.password"), e.getMessage());
    }

    @Test
    void describeKeepsReferencesVisibleAndMasksRealSecrets() throws IOException {
        Profiles p = profiles("{\"profiles\":{\"a\":{\"endpoint\":\"http://x\",\"authBasic\":\"user:plain\",\"authBearer\":\"${TOKEN}\","
                + "\"keystore\":{\"path\":\"k\",\"password\":\"pw\",\"aliasPkPass\":\"pk\"},\"proxy\":{\"password\":\"pp\",\"host\":\"h\"},"
                + "\"headers\":{\"Authorization\":\"Bearer abc\",\"X-Ok\":\"fine\"}}}}");
        String json = Profiles.toJson(p.describe("a"));
        assertTrue(json.contains("\"authBasic\" : \"****\""), json);
        assertTrue(json.contains("\"authBearer\" : \"${TOKEN}\""), json);
        assertTrue(json.contains("\"password\" : \"****\""), json);
        assertTrue(json.contains("\"aliasPkPass\" : \"****\""), json);
        assertTrue(json.contains("\"Authorization\" : \"****\""), json);
        assertTrue(json.contains("\"X-Ok\" : \"fine\""), json);
        for (String secret : new String[]{"plain", "\"pw\"", "\"pk\"", "\"pp\"", "Bearer abc"}) {
            assertFalse(json.contains(secret), secret + " leaked: " + json);
        }
    }

    @Test
    void describeShowsExtendsAndRendersAsText() throws IOException {
        Profiles p = profiles("{\"profiles\":{\"base\":{\"theme\":\"nord\"},\"a\":{\"extends\":\"base\",\"keystore\":{\"password\":\"x\"}}}}");
        JsonNode d = p.describe("a");
        assertEquals("base", d.get("extends").asText());
        String text = String.join("\n", Profiles.render(d, 4));
        assertTrue(text.contains("    extends: base"), text);
        assertTrue(text.contains("    keystore:\n        password: **** (set)"), text);
    }

    @Test
    void unknownKeysWarnAndWrongTypesFail() throws IOException {
        Profiles p = profiles("{\"profiles\":{\"typo\":{\"endpoin\":\"x\",\"proxy\":{\"prt\":1}},"
                + "\"badPort\":{\"proxy\":{\"port\":\"abc\"}},\"badBool\":{\"mtom\":\"maybe\"},\"badObj\":{\"keystore\":\"k.jks\"},"
                + "\"badHdr\":{\"headers\":{\"X\":[1]}},\"badVer\":{\"soapVersion\":\"3\"}}}");
        Profile typo = p.resolve("typo", Collections.emptyMap());
        assertEquals(2, typo.warnings.size());
        assertTrue(typo.warnings.get(0).contains("'endpoin'"));
        assertTrue(typo.warnings.get(1).contains("'proxy.prt'"));
        for (String bad : new String[]{"badPort", "badBool", "badObj", "badHdr", "badVer"}) {
            assertThrows(ConfigException.class, () -> p.resolve(bad, Collections.emptyMap()), bad);
        }
    }

    @Test
    void emptyValuesAreTreatedAsNotSetExceptForArgs() throws IOException {
        Profiles p = profiles("{\"profiles\":{\"a\":{\"endpoint\":\"\",\"keystore\":{\"path\":\"  \"},\"args\":{\"X\":\"\"},\"headers\":{\"H\":\"\"}}}}");
        Profile a = p.resolve("a", Collections.emptyMap());
        assertNull(a.endpoint);
        assertNull(a.keystorePath);
        assertEquals("", a.args.get("X"));
        assertFalse(a.headers.containsKey("H"));
    }

    @Test
    void applyToFillsTheCallConfigAndExpandsHome() throws IOException {
        Profiles p = profiles("{\"profiles\":{\"full\":{\"endpoint\":\"https://e/soap\",\"requestFile\":\"~/req.xml\",\"soapVersion\":\"1.1\","
                + "\"soapAction\":\"urn:a\",\"mtom\":true,\"timeout\":5,\"connectTimeout\":2,\"followRedirects\":true,\"insecure\":true,"
                + "\"authBearer\":\"tok\",\"headers\":{\"X\":\"1\"},\"args\":{\"A\":\"b\"},\"attachments\":{\"cid\":\"~/f.bin\"},"
                + "\"keystore\":{\"path\":\"~/k.p12\",\"type\":\"PKCS12\",\"alias\":\"al\",\"password\":\"pw\",\"aliasPkPass\":\"pk\"},"
                + "\"truststore\":{\"path\":\"t.jks\",\"type\":\"JKS\",\"password\":\"tp\"},"
                + "\"proxy\":{\"type\":\"socks\",\"host\":\"h\",\"port\":\"1080\",\"user\":\"u\",\"password\":\"pp\"}}}}");
        CallConfig c = new CallConfig();
        c.soapVersion = SoapVersion.SOAP_1_2;
        p.resolve("full", Collections.emptyMap()).applyTo(c, "/home/me");
        assertEquals("https://e/soap", c.endpoint);
        assertEquals("/home/me/req.xml", c.requestFile);
        assertEquals(SoapVersion.SOAP_1_1, c.soapVersion);
        assertEquals("urn:a", c.soapAction);
        assertTrue(c.mtom);
        assertEquals(5, c.transport.readTimeoutSeconds);
        assertEquals(2, c.transport.connectTimeoutSeconds);
        assertTrue(c.transport.followRedirects);
        assertTrue(c.transport.tls.insecure);
        assertEquals("tok", c.authBearer);
        assertEquals("1", c.headers.get("x"));
        assertEquals("b", c.args.get("A"));
        assertEquals("/home/me/f.bin", c.attachments.get("cid"));
        assertEquals("/home/me/k.p12", c.transport.tls.keystorePath);
        assertEquals("PKCS12", c.transport.tls.keystoreType);
        assertEquals("al", c.transport.tls.keystoreAlias);
        assertEquals("pw", c.transport.tls.keystorePassword);
        assertEquals("pk", c.transport.tls.keystoreAliasPkPass);
        assertEquals("t.jks", c.transport.tls.truststorePath);
        assertEquals("tp", c.transport.tls.truststorePassword);
        assertEquals("socks", c.transport.proxy.type);
        assertEquals(Integer.valueOf(1080), c.transport.proxy.port);
        assertEquals("pp", c.transport.proxy.password);
    }

    @Test
    void autoInAProfileResetsTheVersion() throws IOException {
        Profile p = profiles("{\"profiles\":{\"a\":{\"soapVersion\":\"auto\"}}}").resolve("a", Collections.emptyMap());
        CallConfig c = new CallConfig();
        c.soapVersion = SoapVersion.SOAP_1_1;
        p.applyTo(c, null);
        assertNull(c.soapVersion);
    }

    @Test
    void defaultFileLocation() {
        assertEquals(Paths.get("/h/.config/soap-cli-profiles.json"), Profiles.defaultFile(Collections.emptyMap(), "/h"));
        assertEquals(Paths.get("/x/p.json"), Profiles.defaultFile(Collections.singletonMap("SOAPCLI_PROFILE_FILE", "/x/p.json"), "/h"));
        assertEquals(Paths.get("soap-cli-profiles.json"), Profiles.defaultFile(Collections.emptyMap(), ""));
    }
}
