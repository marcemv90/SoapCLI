package io.github.marcemv90.soapcli.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The server with workspaces: discovery, what each one keeps apart, the shared library, and what is refused. */
class WorkspacesApiTest {

    private static final String ENV11 = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><ping/></s:Body></s:Envelope>";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tmp;
    Path root;

    private final List<AutoCloseable> toClose = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY).build();

    @BeforeEach
    void workspacesFolder() throws IOException {
        root = Files.createDirectories(tmp.resolve("workspaces"));
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private WebServer start(String... args) throws IOException {
        List<String> all = new ArrayList<>(List.of("--port", "0", "--workspaces-dir", root.toString()));
        all.addAll(List.of(args));
        Map<String, String> env = new HashMap<>();
        env.put("SOAPCLI_PROFILE_FILE", tmp.resolve("not-used.json").toString());
        ServeOptions options = ServeOptions.parse(all.toArray(new String[0]), env, tmp.toString());
        WebServer server = new WebServer(options, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        server.start();
        toClose.add(server);
        return server;
    }

    private WebServer startWithoutWorkspaces() throws IOException {
        Map<String, String> env = new HashMap<>();
        env.put("SOAPCLI_PROFILE_FILE", tmp.resolve("none.json").toString());
        env.put("SOAPCLI_PLACEHOLDERS_FILE", tmp.resolve("none-placeholders.json").toString());
        ServeOptions options = ServeOptions.parse(new String[]{"--port", "0"}, env, tmp.toString());
        WebServer server = new WebServer(options, new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        server.start();
        toClose.add(server);
        return server;
    }

    private Path workspace(String name) throws IOException {
        return Files.createDirectories(root.resolve(name));
    }

    private MockServer mock() {
        MockServer m = new MockServer(r -> Reply.xml("<ok/>"));
        toClose.add(m);
        return m;
    }

    private HttpResponse<String> get(WebServer s, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode getJson(WebServer s, String path) throws Exception {
        return JSON.readTree(get(s, path).body());
    }

    private HttpResponse<String> post(WebServer s, String path, Object body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode postJson(WebServer s, String path, Object body) throws Exception {
        return JSON.readTree(post(s, path, body).body());
    }

    private String raw(WebServer s, String request) throws IOException {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), s.port())) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static Map<String, Object> map(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private static String errorCategory(HttpResponse<String> r) throws IOException {
        return JSON.readTree(r.body()).get("error").get("category").asText();
    }

    // ---- discovery ----------------------------------------------------------------------------------------------------

    @Test
    void everyFolderWithAValidNameIsAWorkspaceAndTheRestIsIgnored() throws Exception {
        workspace("shipping");
        Path billing = workspace("billing");
        Files.writeString(billing.resolve("workspace.json"), "{ // who it is for\n \"description\": \"Billing services\" }");
        Files.writeString(billing.resolve("profiles.json"), "{\"profiles\":{\"a\":{},\"b\":{}}}");
        Files.createDirectories(billing.resolve("requests"));
        Files.writeString(billing.resolve("requests").resolve("one.json"), "{}");
        Files.createDirectories(root.resolve(".git"));
        Files.createDirectories(root.resolve("not a valid name"));
        Files.createDirectories(root.resolve(".removed-old-20200101-000000"));
        Files.writeString(root.resolve("notes.txt"), "a file is not a workspace");

        WebServer s = start();
        JsonNode list = getJson(s, "/api/workspaces");
        assertTrue(list.get("enabled").asBoolean());
        assertEquals(root.toString(), list.get("dir").asText());
        assertEquals(List.of("billing", "shipping"), names(list));
        JsonNode first = list.get("workspaces").get(0);
        assertEquals("Billing services", first.get("description").asText());
        assertEquals(2, first.get("profiles").asInt());
        assertEquals(1, first.get("requests").asInt());
        assertEquals(0, first.get("placeholders").asInt());
        assertFalse(first.get("readOnly").asBoolean());
        assertEquals(0, list.get("workspaces").get(1).get("profiles").asInt(), "no profiles file is zero profiles");

        // a workspace added by hand shows up without a restart
        workspace("archive");
        assertEquals(List.of("archive", "billing", "shipping"), names(getJson(s, "/api/workspaces")));
    }

    private static List<String> names(JsonNode list) {
        List<String> names = new ArrayList<>();
        list.get("workspaces").forEach(w -> names.add(w.get("name").asText()));
        return names;
    }

    @Test
    void aBrokenProfilesFileDoesNotHideTheWorkspace() throws Exception {
        Path ws = workspace("broken");
        Files.writeString(ws.resolve("profiles.json"), "{ \"profiles\": { \"a\": ");
        JsonNode w = getJson(start(), "/api/workspaces").get("workspaces").get(0);
        assertEquals("broken", w.get("name").asText());
        assertNull(w.get("profiles"), "its profiles cannot be counted: the Profiles page says why");
    }

    @Test
    void theInfoOfAWorkspaceSaysWhereItsFilesAre() throws Exception {
        Path ws = workspace("billing");
        Files.writeString(ws.resolve("profiles.json"), "{\"profiles\":{}}");
        WebServer s = start();

        JsonNode scoped = getJson(s, "/api/workspaces/billing/info");
        assertEquals(ws.resolve("profiles.json").toString(), scoped.get("profilesFile").asText());
        assertTrue(scoped.get("profilesFileFound").asBoolean());
        assertEquals(ws.resolve("placeholders.json").toString(), scoped.get("placeholdersFile").asText());
        assertEquals(ws.resolve("attachments").toString(), scoped.get("attachmentsDir").asText());
        assertEquals("billing", scoped.get("workspace").get("name").asText());
        assertTrue(scoped.get("workspacesEnabled").asBoolean());

        JsonNode global = getJson(s, "/api/info");
        assertTrue(global.get("workspacesEnabled").asBoolean());
        assertEquals(root.toString(), global.get("workspacesDir").asText());
        assertFalse(global.has("profilesFile"), "the whole server has no profiles file of its own");
        assertFalse(global.has("workspace"));
    }

    // ---- what is asked of which workspace -----------------------------------------------------------------------------

    @Test
    void whatBelongsToAWorkspaceIsOnlyReachedThroughItsName() throws Exception {
        workspace("billing");
        WebServer s = start();
        HttpResponse<String> r = get(s, "/api/profiles");
        assertEquals(400, r.statusCode());
        assertTrue(r.body().contains("/api/workspaces/{name}/profiles"), r.body());
        assertEquals(400, post(s, "/api/send", map("body", ENV11, "endpoint", "http://127.0.0.1:1/")).statusCode());
        assertEquals(200, get(s, "/api/info").statusCode());
        assertEquals(200, post(s, "/api/format", map("xml", "<a/>")).statusCode());
        assertEquals(200, get(s, "/api/workspaces/billing/profiles").statusCode());
        assertEquals(404, get(s, "/api/workspaces/billing").statusCode());
        assertEquals(404, get(s, "/api/workspaces/billing/nothing").statusCode());
    }

    @Test
    void anUnknownOrBadWorkspaceNameReachesNothing() throws Exception {
        workspace("billing");
        Files.writeString(root.resolve("secret.txt"), "TOP-SECRET");
        Files.writeString(tmp.resolve("profiles.json"), "{\"profiles\":{\"leak\":{}}}");
        WebServer s = start();
        assertEquals(404, get(s, "/api/workspaces/nope/profiles").statusCode());
        assertTrue(get(s, "/api/workspaces/nope/profiles").body().contains("no workspace 'nope'"));
        for (String name : new String[]{"..", "%2e%2e", ".", "a%2Fb", "billing%2F..", ".git", "bill%00ing", "%2e%2e%2fworkspaces"}) {
            String answer = raw(s, "GET /api/workspaces/" + name + "/profiles HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
            assertFalse(answer.startsWith("HTTP/1.1 200"), name + " -> " + answer.split("\r\n")[0]);
            assertFalse(answer.contains("leak") || answer.contains("TOP-SECRET"), name);
        }
        assertEquals(404, post(s, "/api/workspaces/../send", map("body", ENV11)).statusCode());
    }

    @Test
    void aServerWithoutWorkspacesAnswersAsBeforeAndSaysSo() throws Exception {
        WebServer s = startWithoutWorkspaces();
        JsonNode list = getJson(s, "/api/workspaces");
        assertFalse(list.get("enabled").asBoolean());
        assertEquals(0, list.get("workspaces").size());
        assertEquals(404, get(s, "/api/workspaces/billing/info").statusCode());
        HttpResponse<String> library = get(s, "/api/requests");
        assertEquals(404, library.statusCode());
        assertTrue(library.body().contains("kept in the browser"), library.body());
        JsonNode info = getJson(s, "/api/info");
        assertFalse(info.get("workspacesEnabled").asBoolean());
        assertTrue(info.has("profilesFile") && info.has("attachmentsDir"), "the single set of files is described, as it always was");
        assertEquals(200, get(s, "/api/profiles").statusCode());
        assertEquals(403, post(s, "/api/workspaces/create", map("name", "x")).statusCode());
    }

    // ---- each workspace keeps its own ---------------------------------------------------------------------------------

    @Test
    void profilesAndPlaceholdersAreKeptApartPerWorkspace() throws Exception {
        Path billing = workspace("billing");
        Path shipping = workspace("shipping");
        Files.writeString(billing.resolve("profiles.json"), "{\"profiles\":{\"acc\":{\"endpoint\":\"http://billing-acc\"}}}");
        Files.writeString(shipping.resolve("profiles.json"), "{\"profiles\":{\"prod\":{\"endpoint\":\"http://shipping-prod\"},\"dev\":{}}}");
        Files.writeString(shipping.resolve("placeholders.json"), "{\"placeholders\":{\"ENV\":{\"options\":[\"a\",\"b\"]}}}");
        WebServer s = start();

        assertEquals(1, getJson(s, "/api/workspaces/billing/profiles").get("profiles").size());
        assertEquals(2, getJson(s, "/api/workspaces/shipping/profiles").get("profiles").size());
        assertEquals(0, getJson(s, "/api/workspaces/billing/placeholders").get("placeholders").size());
        assertEquals("ENV", getJson(s, "/api/workspaces/shipping/placeholders").get("placeholders").get(0).get("name").asText());

        // editing one never touches the other
        String revision = getJson(s, "/api/workspaces/billing/profiles").get("revision").asText();
        HttpResponse<String> saved = post(s, "/api/workspaces/billing/profiles/save",
                map("name", "acc", "revision", revision, "create", false, "definition", map("endpoint", "http://changed")));
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(Files.readString(billing.resolve("profiles.json")).contains("http://changed"));
        assertTrue(Files.readString(shipping.resolve("profiles.json")).contains("http://shipping-prod"));
        String shippingRevision = getJson(s, "/api/workspaces/shipping/profiles").get("revision").asText();
        assertEquals(404, post(s, "/api/workspaces/shipping/profiles/save",
                map("name", "acc", "revision", shippingRevision, "create", false, "definition", map())).statusCode(), "'acc' is billing's, not shipping's");

        // a profile that is not in a workspace cannot be used from it
        MockServer target = mock();
        HttpResponse<String> other = post(s, "/api/workspaces/shipping/send", map("profile", "acc", "body", ENV11));
        assertEquals(400, other.statusCode());
        assertTrue(other.body().contains("acc"), other.body());
        assertTrue(target.requests.isEmpty());
    }

    @Test
    void aWorkspaceSendsThroughItsOwnProfileAndItsRelativePathsStartInItsFolder() throws Exception {
        MockServer target = mock();
        Path billing = workspace("billing");
        Files.createDirectories(billing.resolve("requests"));
        Files.createDirectories(billing.resolve("attachments"));
        Files.writeString(billing.resolve("requests").resolve("order.xml"),
                ENV11.replace("<ping/>", "<v>cid:doc</v>"));
        Files.writeString(billing.resolve("attachments").resolve("doc.txt"), "hello");
        Files.writeString(billing.resolve("profiles.json"), "{\"profiles\":{\"acc\":{\"endpoint\":\"" + target.url("/soap")
                + "\",\"requestFile\":\"requests/order.xml\",\"attachments\":{\"doc\":\"attachments/doc.txt\"},"
                + "\"headers\":{\"X-Workspace\":\"billing\"}}}}");
        WebServer s = start();

        JsonNode sent = postJson(s, "/api/workspaces/billing/send", map("profile", "acc"));
        assertEquals(200, sent.get("status").asInt(), sent.toString());
        assertTrue(target.requests.get(0).bodyText().contains("<v>aGVsbG8=</v>"), "the file named relative to the workspace was found: "
                + target.requests.get(0).bodyText());
        assertEquals("billing", target.requests.get(0).header("X-Workspace"));

        // "Load its request file" of the profiles page
        HttpResponse<String> xml = get(s, "/api/workspaces/billing/profiles/acc/request");
        assertEquals(200, xml.statusCode());
        assertTrue(xml.body().contains("cid:doc"));
    }

    @Test
    void secretsOfAWorkspaceFeedItsProfilesAndNeverComeBack() throws Exception {
        MockServer target = mock();
        Path ws = workspace("billing");
        Files.writeString(ws.resolve("secrets.env"), "# kept out of the profiles\nACC_CREDENTIALS=svc:from-the-workspace\n");
        Files.writeString(ws.resolve("profiles.json"), "{\"profiles\":{\"acc\":{\"endpoint\":\"" + target.url("/")
                + "\",\"authBasic\":\"${ACC_CREDENTIALS}\"}}}");
        Path other = workspace("other");
        Files.writeString(other.resolve("profiles.json"), "{\"profiles\":{\"acc\":{\"endpoint\":\"" + target.url("/")
                + "\",\"authBasic\":\"${ACC_CREDENTIALS:-nobody:nothing}\"}}}");
        WebServer s = start();

        String answer = post(s, "/api/workspaces/billing/send", map("profile", "acc", "body", ENV11)).body();
        assertEquals("Basic " + Base64.getEncoder().encodeToString("svc:from-the-workspace".getBytes(StandardCharsets.UTF_8)),
                target.requests.get(0).header("Authorization"));
        assertFalse(answer.contains("from-the-workspace"), answer);
        assertFalse(getJson(s, "/api/workspaces/billing/profiles").toString().contains("from-the-workspace"));

        // another workspace does not see it
        post(s, "/api/workspaces/other/send", map("profile", "acc", "body", ENV11));
        assertEquals("Basic " + Base64.getEncoder().encodeToString("nobody:nothing".getBytes(StandardCharsets.UTF_8)),
                target.requests.get(1).header("Authorization"));

        // the check made when a profile is saved knows about them too: no "variable not set" warning
        String revision = getJson(s, "/api/workspaces/billing/profiles").get("revision").asText();
        JsonNode saved = postJson(s, "/api/workspaces/billing/profiles/save", map("name", "acc", "revision", revision, "create", false,
                "definition", map("endpoint", target.url("/"), "authBasic", "${ACC_CREDENTIALS}", "timeout", "${ACC_CREDENTIALS}")));
        assertFalse(saved.toString().contains("could not be checked"), saved.toString());
    }

    @Test
    void attachmentsByPathAreConfinedToTheAttachmentsFolderOfTheWorkspaceAskedFor() throws Exception {
        Path billing = workspace("billing");
        Path shipping = workspace("shipping");
        for (Path ws : new Path[]{billing, shipping}) {
            Files.createDirectories(ws.resolve("attachments"));
        }
        Files.writeString(billing.resolve("attachments").resolve("doc.txt"), "billing-file");
        Files.writeString(shipping.resolve("attachments").resolve("doc.txt"), "shipping-file");
        Files.writeString(billing.resolve("profiles.json"), "{\"profiles\":{}}"); // next to attachments/, not inside it
        WebServer s = start();

        JsonNode own = postJson(s, "/api/workspaces/billing/attachments/check", map("path", "doc.txt"));
        assertEquals(billing.resolve("attachments").resolve("doc.txt").toRealPath().toString(), own.get("resolved").asText());
        assertEquals(shipping.resolve("attachments").resolve("doc.txt").toRealPath().toString(),
                postJson(s, "/api/workspaces/shipping/attachments/check", map("path", "doc.txt")).get("resolved").asText());

        for (String path : new String[]{"../profiles.json", "../../shipping/attachments/doc.txt",
                shipping.resolve("attachments").resolve("doc.txt").toString(), billing.resolve("profiles.json").toString()}) {
            HttpResponse<String> r = post(s, "/api/workspaces/billing/attachments/check", map("path", path));
            assertEquals(403, r.statusCode(), path + " -> " + r.body());
            assertFalse(r.body().contains("shipping-file"));
        }

        MockServer target = mock();
        Map<String, Object> send = map("endpoint", target.url("/"), "body", ENV11.replace("<ping/>", "<v>cid:f</v>"),
                "attachments", List.of(map("cid", "f", "path", "doc.txt")));
        postJson(s, "/api/workspaces/shipping/send", send);
        assertTrue(target.requests.get(0).bodyText().contains(Base64.getEncoder().encodeToString("shipping-file".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void aWorkspaceWithoutAnAttachmentsFolderSaysSo() throws Exception {
        workspace("bare");
        HttpResponse<String> r = post(start(), "/api/workspaces/bare/attachments/check", map("path", "doc.txt"));
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("no attachments folder"), r.body());
    }

    @Test
    void aWorkspaceCanLimitWhereItCallsOnTopOfWhatTheServerAllows() throws Exception {
        MockServer target = mock();
        Path locked = workspace("locked");
        Files.writeString(locked.resolve("workspace.json"), "{ \"allowedHosts\": [\"only.example.com\"] }");
        Path open = workspace("open");
        Map<String, Object> send = map("endpoint", target.url("/"), "body", ENV11);

        WebServer s = start();
        HttpResponse<String> refused = post(s, "/api/workspaces/locked/send", send);
        assertEquals(403, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("only.example.com"), refused.body());
        assertTrue(target.requests.isEmpty());
        assertEquals(200, post(s, "/api/workspaces/open/send", send).statusCode(), "another workspace is not limited");
        assertEquals(List.of("only.example.com"), strings(getJson(s, "/api/workspaces").get("workspaces").get(0).get("allowedHosts")));

        // the server's own list still applies to every workspace: the workspace cannot widen it
        Files.writeString(open.resolve("workspace.json"), "{ \"allowedHosts\": [\"127.0.0.1\"] }");
        WebServer strict = start("--allow-host", "other.example.com");
        assertEquals(403, post(strict, "/api/workspaces/open/send", send).statusCode());
    }

    private static List<String> strings(JsonNode array) {
        List<String> list = new ArrayList<>();
        array.forEach(n -> list.add(n.asText()));
        return list;
    }

    @Test
    void aReadOnlyWorkspaceCanBeUsedButNotChanged() throws Exception {
        MockServer target = mock();
        Path ws = workspace("prod");
        Files.writeString(ws.resolve("workspace.json"), "{ \"description\": \"Production\", \"readOnly\": true }");
        Files.writeString(ws.resolve("profiles.json"), "{\"profiles\":{\"p\":{\"endpoint\":\"" + target.url("/") + "\"}}}");
        WebServer s = start();

        JsonNode profiles = getJson(s, "/api/workspaces/prod/profiles");
        assertFalse(profiles.get("editable").asBoolean());
        assertTrue(profiles.get("readOnlyReason").asText().contains("read-only"), profiles.toString());
        assertEquals(403, post(s, "/api/workspaces/prod/profiles/save", map("name", "p", "revision", profiles.get("revision").asText(),
                "create", false, "definition", map("endpoint", "http://evil"))).statusCode());
        assertEquals(403, post(s, "/api/workspaces/prod/placeholders/save", map("name", "ENV", "revision", "missing", "create", true,
                "options", List.of(map("value", "a")))).statusCode());
        assertTrue(Files.readString(ws.resolve("profiles.json")).contains(target.url("/")));

        JsonNode library = getJson(s, "/api/workspaces/prod/requests");
        assertFalse(library.get("editable").asBoolean());
        assertEquals(403, post(s, "/api/workspaces/prod/requests/save", map("name", "x", "draft", map("body", ENV11))).statusCode());

        assertEquals(200, post(s, "/api/workspaces/prod/send", map("profile", "p", "body", ENV11)).statusCode(), "but it can be used");
        assertTrue(getJson(s, "/api/workspaces").get("workspaces").get(0).get("readOnly").asBoolean());

        // an administrator lifting the flag by hand applies at once
        Files.writeString(ws.resolve("workspace.json"), "{ \"readOnly\": false }");
        assertTrue(getJson(s, "/api/workspaces/prod/profiles").get("editable").asBoolean());
    }

    @Test
    void aWorkspaceJsonThatCannotBeReadMakesTheWorkspaceReadOnlyAndSendsNothing() throws Exception {
        MockServer target = mock();
        Path ws = workspace("oops");
        Files.writeString(ws.resolve("workspace.json"), "{ \"allowedHosts\": \"not a list\" ");
        WebServer s = start();
        JsonNode summary = getJson(s, "/api/workspaces").get("workspaces").get(0);
        assertTrue(summary.get("error").asText().contains("workspace.json"), summary.toString());
        assertTrue(summary.get("readOnly").asBoolean());
        assertFalse(getJson(s, "/api/workspaces/oops/profiles").get("editable").asBoolean());
        HttpResponse<String> r = post(s, "/api/workspaces/oops/send", map("endpoint", target.url("/"), "body", ENV11));
        assertEquals(400, r.statusCode(), "a limit that cannot be read is not silently dropped");
        assertTrue(target.requests.isEmpty());

        Files.writeString(ws.resolve("workspace.json"), "{ \"readOnly\": \"yes\" }");
        assertTrue(getJson(s, "/api/workspaces").get("workspaces").get(0).get("error").asText().contains("true or false"));
    }

    // ---- creating and removing ------------------------------------------------------------------------------------------

    @Test
    void aWorkspaceCanBeCreatedFromTheUi() throws Exception {
        WebServer s = start();
        assertTrue(getJson(s, "/api/workspaces").get("manageable").asBoolean());
        HttpResponse<String> r = post(s, "/api/workspaces/create", map("name", "billing", "description", "  Billing services  "));
        assertEquals(200, r.statusCode(), r.body());
        assertEquals("billing", JSON.readTree(r.body()).get("name").asText());

        Path ws = root.resolve("billing");
        assertTrue(Files.isDirectory(ws.resolve("attachments")) && Files.isDirectory(ws.resolve("requests")));
        JsonNode meta = JSON.readTree(Files.readString(ws.resolve("workspace.json")));
        assertEquals("Billing services", meta.get("description").asText());
        assertFalse(meta.get("readOnly").asBoolean());
        assertEquals(0, meta.get("allowedHosts").size());
        assertEquals(List.of("billing"), names(getJson(s, "/api/workspaces")));
        assertEquals(200, get(s, "/api/workspaces/billing/profiles").statusCode(), "and it is ready to be used");

        assertEquals(409, post(s, "/api/workspaces/create", map("name", "billing")).statusCode());
        assertEquals("conflict", errorCategory(post(s, "/api/workspaces/create", map("name", "billing"))));
    }

    @Test
    void badWorkspaceNamesAreRefusedBeforeTouchingTheDisk() throws Exception {
        WebServer s = start();
        for (String name : new String[]{"", " ", "../escape", "a/b", ".hidden", "-lead", "has space", "x".repeat(65), "tab\t", "ünï"}) {
            HttpResponse<String> r = post(s, "/api/workspaces/create", map("name", name));
            assertEquals(400, r.statusCode(), "'" + name + "' -> " + r.body());
        }
        assertEquals(400, post(s, "/api/workspaces/create", map("name", "ok", "description", "x".repeat(301))).statusCode());
        assertEquals(400, post(s, "/api/workspaces/create", map("name", "ok", "description", "two\nlines")).statusCode());
        assertEquals(400, post(s, "/api/workspaces/create", new HashMap<>()).statusCode());
        try (Stream<Path> files = Files.list(root)) {
            assertEquals(List.of(), files.collect(Collectors.toList()), "nothing was created");
        }
        assertFalse(Files.exists(tmp.resolve("escape")));
    }

    @Test
    void removingAWorkspaceSetsItAsideInsteadOfDeletingIt() throws Exception {
        Path ws = workspace("old");
        Files.writeString(ws.resolve("profiles.json"), "{\"profiles\":{\"keep\":{}}}");
        Files.createDirectories(ws.resolve("attachments"));
        Files.writeString(ws.resolve("attachments").resolve("f.txt"), "precious");
        workspace("stays");
        WebServer s = start();

        HttpResponse<String> r = post(s, "/api/workspaces/remove", map("name", "old"));
        assertEquals(200, r.statusCode(), r.body());
        String movedTo = JSON.readTree(r.body()).get("movedTo").asText();
        assertTrue(movedTo.startsWith(".removed-old-"), movedTo);
        assertEquals("precious", Files.readString(root.resolve(movedTo).resolve("attachments").resolve("f.txt")), "nothing is deleted");
        assertFalse(Files.exists(ws));
        assertEquals(List.of("stays"), names(getJson(s, "/api/workspaces")), "it no longer shows up");
        assertEquals(404, get(s, "/api/workspaces/old/profiles").statusCode());
        assertEquals(404, post(s, "/api/workspaces/remove", map("name", "old")).statusCode());
        assertEquals(400, post(s, "/api/workspaces/remove", new HashMap<>()).statusCode());

        // the name can be used again
        assertEquals(200, post(s, "/api/workspaces/create", map("name", "old")).statusCode());
    }

    @Test
    void creatingAndRemovingFollowTheServersPermissionToEdit() throws Exception {
        workspace("billing");
        WebServer network = start("--host", "0.0.0.0");
        JsonNode list = getJson(network, "/api/workspaces");
        assertFalse(list.get("manageable").asBoolean());
        assertTrue(list.get("manageReason").asText().contains("--allow-config-edit"), list.toString());
        assertEquals(403, post(network, "/api/workspaces/create", map("name", "new")).statusCode());
        assertEquals(403, post(network, "/api/workspaces/remove", map("name", "billing")).statusCode());
        assertTrue(Files.isDirectory(root.resolve("billing")));
        assertFalse(Files.exists(root.resolve("new")));

        assertEquals(200, post(start("--host", "0.0.0.0", "--allow-config-edit"), "/api/workspaces/create", map("name", "new")).statusCode());
        assertEquals(403, post(start("--no-config-edit"), "/api/workspaces/create", map("name", "off")).statusCode());
    }

    @Test
    void workspaceEndpointsOnlyTakeJsonPosts() throws Exception {
        workspace("billing");
        WebServer s = start();
        for (String path : new String[]{"/api/workspaces/create", "/api/workspaces/remove", "/api/workspaces/billing/requests/save",
                "/api/workspaces/billing/requests/delete"}) {
            HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path))
                    .header("Content-Type", "text/plain").POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"x\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(415, r.statusCode(), path + ": a cross-site form post cannot change the workspaces");
            assertEquals(405, get(s, path).statusCode(), path);
        }
        assertTrue(Files.isDirectory(root.resolve("billing")));
    }

    // ---- the library ----------------------------------------------------------------------------------------------------

    private static Map<String, Object> draft(String endpoint, String body) {
        Map<String, Object> draft = map("endpoint", endpoint, "body", body, "soapVersion", "auto", "soapAction", "urn:GetOrder",
                "profile", "acc", "mtom", false, "args", map("ORDER_ID", "42"),
                "headers", List.of(map("id", "h1", "name", "X-Env", "value", "acc", "enabled", true)),
                "options", map("timeoutSeconds", 30, "followRedirects", false, "insecure", false),
                "auth", map("kind", "none", "user", ""),
                "attachments", List.of(map("cid", "doc", "path", "doc.txt")));
        return draft;
    }

    @Test
    void aRequestCanBeSavedListedOpenedChangedAndRemoved() throws Exception {
        Path ws = workspace("billing");
        WebServer s = start();
        assertEquals(0, getJson(s, "/api/workspaces/billing/requests").get("requests").size());

        HttpResponse<String> created = post(s, "/api/workspaces/billing/requests/save",
                map("name", "Get order #1", "folder", "Orders", "draft", draft("http://acc/soap", ENV11)));
        assertEquals(200, created.statusCode(), created.body());
        JsonNode c = JSON.readTree(created.body());
        String id = c.get("id").asText();
        assertTrue(id.startsWith("get-order-1-"), "readable in a folder listing: " + id);
        assertEquals(0, c.get("warnings").size());
        Path file = ws.resolve("requests").resolve(id + ".json");
        assertTrue(Files.isRegularFile(file));

        JsonNode list = getJson(s, "/api/workspaces/billing/requests");
        assertTrue(list.get("editable").asBoolean());
        JsonNode row = list.get("requests").get(0);
        assertEquals(id, row.get("id").asText());
        assertEquals("Get order #1", row.get("name").asText());
        assertEquals("Orders", row.get("folder").asText());
        assertEquals("http://acc/soap", row.get("endpoint").asText());
        assertEquals("urn:GetOrder", row.get("soapAction").asText());
        assertEquals("acc", row.get("profile").asText());
        assertEquals(c.get("revision").asText(), row.get("revision").asText());
        assertFalse(row.has("draft") || row.has("body"), "a list does not carry every body");
        assertEquals(1, getJson(s, "/api/workspaces").get("workspaces").get(0).get("requests").asInt());

        JsonNode opened = getJson(s, "/api/workspaces/billing/requests/" + id);
        assertEquals(ENV11, opened.get("draft").get("body").asText());
        assertEquals("42", opened.get("draft").get("args").get("ORDER_ID").asText());
        assertEquals("doc.txt", opened.get("draft").get("attachments").get(0).get("path").asText());
        assertEquals("X-Env", opened.get("draft").get("headers").get(0).get("name").asText());
        assertEquals(30, opened.get("draft").get("options").get("timeoutSeconds").asInt());

        // the file is plain JSON that a person can read, and the folder holds nothing else
        JsonNode onDisk = JSON.readTree(Files.readString(file));
        assertEquals("soapcli", onDisk.get("app").asText());
        assertEquals(1, onDisk.get("version").asInt());
        try {
            assertEquals("rw-r--r--", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)),
                    "a shared file can be read by whoever backs the folder up");
        } catch (UnsupportedOperationException ignored) {
            // not POSIX
        }
        try (Stream<Path> files = Files.list(ws.resolve("requests"))) {
            assertEquals(1, files.count(), "no temporary file is left behind");
        }

        // change it (rename, move to another folder, new body)
        HttpResponse<String> updated = post(s, "/api/workspaces/billing/requests/save", map("id", id, "revision", c.get("revision").asText(),
                "name", "Get an order", "folder", "", "draft", draft("http://acc/soap", ENV11.replace("<ping/>", "<pong/>"))));
        assertEquals(200, updated.statusCode(), updated.body());
        JsonNode u = JSON.readTree(updated.body());
        assertEquals(id, u.get("id").asText(), "the id, and so the file, stay");
        assertFalse(u.get("revision").asText().equals(c.get("revision").asText()));
        JsonNode again = getJson(s, "/api/workspaces/billing/requests/" + id);
        assertEquals("Get an order", again.get("name").asText());
        assertEquals("", again.get("folder").asText());
        assertTrue(again.get("draft").get("body").asText().contains("<pong/>"));

        // remove it
        assertEquals(409, post(s, "/api/workspaces/billing/requests/delete", map("id", id, "revision", c.get("revision").asText())).statusCode(),
                "with the revision of an older version it is refused");
        assertTrue(Files.exists(file));
        assertEquals(200, post(s, "/api/workspaces/billing/requests/delete", map("id", id, "revision", u.get("revision").asText())).statusCode());
        assertFalse(Files.exists(file));
        assertEquals(404, get(s, "/api/workspaces/billing/requests/" + id).statusCode());
        assertEquals(404, post(s, "/api/workspaces/billing/requests/delete", map("id", id, "revision", "x")).statusCode());
    }

    @Test
    void aSavedRequestIsOpenedWithTheSameBodyItWasSavedWith() throws Exception {
        workspace("billing");
        WebServer s = start();
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + ENV11.replace("<ping/>", "<n>ñandú \"quoted\" &amp; 日本\\</n>") + "\n";
        String id = postJson(s, "/api/workspaces/billing/requests/save", map("name", "Unicode", "draft", draft("http://x", body))).get("id").asText();
        assertEquals(body, getJson(s, "/api/workspaces/billing/requests/" + id).get("draft").get("body").asText());
    }

    @Test
    void twoPeopleChangingTheSameRequestCannotOverwriteEachOther() throws Exception {
        workspace("billing");
        WebServer s = start();
        JsonNode created = postJson(s, "/api/workspaces/billing/requests/save", map("name", "Shared", "draft", draft("http://one", ENV11)));
        String id = created.get("id").asText();
        String revision = created.get("revision").asText();

        // the first person saves
        assertEquals(200, post(s, "/api/workspaces/billing/requests/save",
                map("id", id, "revision", revision, "name", "Shared", "draft", draft("http://first", ENV11))).statusCode());
        // the second one still has the old revision
        HttpResponse<String> stale = post(s, "/api/workspaces/billing/requests/save",
                map("id", id, "revision", revision, "name", "Shared", "draft", draft("http://second", ENV11)));
        assertEquals(409, stale.statusCode(), stale.body());
        assertEquals("conflict", errorCategory(stale));
        assertEquals("http://first", getJson(s, "/api/workspaces/billing/requests/" + id).get("draft").get("endpoint").asText());

        assertEquals(400, post(s, "/api/workspaces/billing/requests/save", map("id", id, "name", "x", "draft", draft("http://x", ENV11))).statusCode(),
                "changing a request needs the revision it was opened at");
        assertEquals(404, post(s, "/api/workspaces/billing/requests/save",
                map("id", "ghost-12345", "revision", "x", "name", "x", "draft", draft("http://x", ENV11))).statusCode());
    }

    @Test
    void credentialsDoNotEndUpInTheSharedLibrary() throws Exception {
        Path ws = workspace("billing");
        WebServer s = start();
        Map<String, Object> d = draft("http://acc", ENV11);
        d.put("auth", map("kind", "basic", "user", "alice", "password", "S3cr3t!", "token", "T0ken"));
        d.put("headers", List.of(
                map("id", "1", "name", "X-Api-Key", "value", "key-123", "enabled", true),
                map("id", "2", "name", "Authorization", "value", "Bearer abc", "enabled", true),
                map("id", "3", "name", "X-Env", "value", "acc", "enabled", true),
                map("id", "4", "name", "X-Empty-Token", "value", "", "enabled", true)));
        d.put("args", map("ORDER_ID", "42", "DB_PASSWORD", "hunter2"));
        d.put("attachments", List.of(map("cid", "f", "path", "doc.txt"), map("cid", "g", "contentBase64", "AAAA", "filename", "x"),
                map("cid", "h", "path", "  ")));
        d.put("somethingElse", "not part of a request");

        JsonNode r = postJson(s, "/api/workspaces/billing/requests/save", map("name", "Careful", "draft", d));
        String warnings = r.get("warnings").toString();
        assertTrue(warnings.contains("password or token") && warnings.contains("X-Api-Key") && warnings.contains("Authorization")
                && warnings.contains("DB_PASSWORD"), warnings);
        assertFalse(warnings.contains("X-Env") || warnings.contains("X-Empty-Token"), warnings);

        String text = Files.readString(ws.resolve("requests").resolve(r.get("id").asText() + ".json"));
        for (String secret : new String[]{"S3cr3t!", "T0ken", "key-123", "Bearer abc", "hunter2", "AAAA", "somethingElse"}) {
            assertFalse(text.contains(secret), secret + " must not be stored: " + text);
        }
        JsonNode saved = getJson(s, "/api/workspaces/billing/requests/" + r.get("id").asText()).get("draft");
        assertEquals("alice", saved.get("auth").get("user").asText());
        assertEquals("basic", saved.get("auth").get("kind").asText());
        assertEquals("", saved.get("headers").get(0).get("value").asText(), "the header stays, empty, to be filled in");
        assertEquals("acc", saved.get("headers").get(2).get("value").asText());
        assertEquals("42", saved.get("args").get("ORDER_ID").asText());
        assertFalse(saved.get("args").has("DB_PASSWORD"));
        assertEquals(1, saved.get("attachments").size(), "only attachments by path are kept");
    }

    @Test
    void badRequestsToSaveAreRefused() throws Exception {
        Path ws = workspace("billing");
        WebServer s = start();
        Map<String, Object> ok = draft("http://x", ENV11);
        for (Object[] c : new Object[][]{
                {map("draft", ok), "Give the request a name"},
                {map("name", "  ", "draft", ok), "Give the request a name"},
                {map("name", "x".repeat(121), "draft", ok), "at most 120"},
                {map("name", "tab\there", "draft", ok), "control characters"},
                {map("name", "ok", "folder", "f".repeat(81), "draft", ok), "at most 80"},
                {map("name", "ok"), "no request to save"},
                {map("name", "ok", "draft", map("endpoint", "http://x")), "needs a body"},
                {map("name", "ok", "draft", "text"), "no request to save"},
        }) {
            HttpResponse<String> r = post(s, "/api/workspaces/billing/requests/save", c[0]);
            assertEquals(400, r.statusCode(), c[0] + " -> " + r.body());
            assertTrue(r.body().contains((String) c[1]), c[1] + " -> " + r.body());
        }
        assertFalse(Files.exists(ws.resolve("requests")), "nothing was written");
        // a request is a small file
        HttpResponse<String> big = post(s, "/api/workspaces/billing/requests/save",
                map("name", "huge", "draft", draft("http://x", ENV11.replace("<ping/>", "<a>" + "x".repeat(6 * 1024 * 1024) + "</a>"))));
        assertEquals(413, big.statusCode(), big.body().length() > 300 ? big.body().substring(0, 300) : big.body());
    }

    @Test
    void anIdCanOnlyBeAFileNameOfTheRequestsFolder() throws Exception {
        Path ws = workspace("billing");
        Files.writeString(ws.resolve("secret.json"), "{\"draft\":{\"body\":\"TOP-SECRET\"}}");
        Files.writeString(root.resolve("outside.json"), "{\"draft\":{\"body\":\"TOP-SECRET\"}}");
        WebServer s = start();
        for (String id : new String[]{"..%2fsecret", "%2e%2e/secret", "../secret", "..", ".hidden", "a%2Fb", "save", "delete", "x".repeat(102)}) {
            String answer = raw(s, "GET /api/workspaces/billing/requests/" + id + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n");
            assertFalse(answer.startsWith("HTTP/1.1 200"), id + " -> " + answer.split("\r\n")[0]);
            assertFalse(answer.contains("TOP-SECRET"), id);
        }
        for (String id : new String[]{"../secret", "..\\secret", "a/b", ".", ".x", "save"}) {
            assertEquals(400, post(s, "/api/workspaces/billing/requests/save",
                    map("id", id, "revision", "x", "name", "x", "draft", draft("http://x", ENV11))).statusCode(), id);
            assertEquals(400, post(s, "/api/workspaces/billing/requests/delete", map("id", id, "revision", "x")).statusCode(), id);
        }
        assertTrue(Files.exists(ws.resolve("secret.json")) && Files.exists(root.resolve("outside.json")));
    }

    @Test
    void filesDroppedIntoTheFolderByHandAreListedAndBrokenOnesAreReported() throws Exception {
        Path requests = Files.createDirectories(workspace("billing").resolve("requests"));
        Files.writeString(requests.resolve("by-hand.json"), String.join("\n",
                "{ // written by an administrator",
                "  \"name\": \"By hand\", \"folder\": \"Admin\",",
                "  \"draft\": { \"endpoint\": \"http://hand\", \"body\": \"<a/>\", }",
                "}"));
        Files.writeString(requests.resolve("broken.json"), "{ \"name\": ");
        Files.writeString(requests.resolve("no-body.json"), "{ \"name\": \"No body\", \"draft\": {} }");
        Files.writeString(requests.resolve("notes.txt"), "not a request");
        Files.writeString(requests.resolve("has space.json"), "{}");
        Files.createDirectories(requests.resolve("sub.json"));
        WebServer s = start();

        JsonNode list = getJson(s, "/api/workspaces/billing/requests").get("requests");
        Map<String, JsonNode> byId = new HashMap<>();
        list.forEach(r -> byId.put(r.get("id").asText(), r));
        assertEquals(3, byId.size(), byId.keySet().toString());
        assertEquals("By hand", byId.get("by-hand").get("name").asText());
        assertEquals("http://hand", byId.get("by-hand").get("endpoint").asText());
        assertFalse(byId.get("by-hand").has("error"));
        assertTrue(byId.get("broken").has("error"), "a file that cannot be read says why, so it can be fixed");
        assertTrue(byId.get("no-body").get("error").asText().contains("body"));
        assertEquals("broken", byId.get("broken").get("name").asText(), "it is listed by its file name");

        assertEquals("<a/>", getJson(s, "/api/workspaces/billing/requests/by-hand").get("draft").get("body").asText());
        assertEquals(400, get(s, "/api/workspaces/billing/requests/broken").statusCode());
        // listed in order: by folder, then by name
        assertEquals("broken", list.get(0).get("id").asText());
        assertEquals("by-hand", list.get(2).get("id").asText());
    }

    @Test
    void theLibraryOfEachWorkspaceIsItsOwn() throws Exception {
        workspace("billing");
        workspace("shipping");
        WebServer s = start();
        String id = postJson(s, "/api/workspaces/billing/requests/save", map("name", "Mine", "draft", draft("http://x", ENV11))).get("id").asText();
        assertEquals(1, getJson(s, "/api/workspaces/billing/requests").get("requests").size());
        assertEquals(0, getJson(s, "/api/workspaces/shipping/requests").get("requests").size());
        assertEquals(404, get(s, "/api/workspaces/shipping/requests/" + id).statusCode());
        assertEquals(404, post(s, "/api/workspaces/shipping/requests/delete", map("id", id, "revision", "x")).statusCode());
    }

    @Test
    void sharingRequestsDoesNotNeedPermissionToEditProfilesButNoConfigEditForbidsIt() throws Exception {
        workspace("billing");
        Map<String, Object> request = map("name", "Shared", "draft", draft("http://x", ENV11));

        WebServer network = start("--host", "0.0.0.0");
        assertFalse(getJson(network, "/api/workspaces/billing/profiles").get("editable").asBoolean(), "profiles stay protected on a network address");
        assertEquals(200, post(network, "/api/workspaces/billing/requests/save", request).statusCode(), "saved requests can be shared");

        WebServer off = start("--no-config-edit");
        JsonNode library = getJson(off, "/api/workspaces/billing/requests");
        assertFalse(library.get("editable").asBoolean());
        assertTrue(library.get("readOnlyReason").asText().contains("--no-config-edit"));
        assertEquals(403, post(off, "/api/workspaces/billing/requests/save", request).statusCode());
        assertEquals(1, library.get("requests").size(), "it can still be read");
    }

    @Test
    void aReadOnlyFolderCannotBeWrittenTo() throws Exception {
        Path requests = Files.createDirectories(workspace("billing").resolve("requests"));
        requests.toFile().setWritable(false);
        org.junit.jupiter.api.Assumptions.assumeFalse(Files.isWritable(requests), "this user can write to read-only folders (root?)");
        try {
            WebServer s = start();
            JsonNode library = getJson(s, "/api/workspaces/billing/requests");
            assertFalse(library.get("editable").asBoolean());
            assertTrue(library.get("readOnlyReason").asText().contains("not writable"), library.toString());
            assertEquals(403, post(s, "/api/workspaces/billing/requests/save", map("name", "x", "draft", draft("http://x", ENV11))).statusCode());
        } finally {
            requests.toFile().setWritable(true);
        }
    }

    @Test
    void aSavedRequestSendsLikeAnyOther() throws Exception {
        // what the page does when it opens one: GET it, then send what it holds
        MockServer target = mock();
        Path ws = workspace("billing");
        Files.createDirectories(ws.resolve("attachments"));
        Files.writeString(ws.resolve("attachments").resolve("doc.txt"), "hello");
        WebServer s = start();
        String body = ENV11.replace("<ping/>", "<v>cid:doc</v>");
        String id = postJson(s, "/api/workspaces/billing/requests/save", map("name", "Upload", "draft", draft(target.url("/soap"), body))).get("id").asText();

        JsonNode draft = getJson(s, "/api/workspaces/billing/requests/" + id).get("draft");
        Map<String, Object> send = map("endpoint", draft.get("endpoint").asText(), "body", draft.get("body").asText(),
                "attachments", List.of(map("cid", "doc", "path", draft.get("attachments").get(0).get("path").asText())));
        assertEquals(200, postJson(s, "/api/workspaces/billing/send", send).get("status").asInt());
        assertTrue(target.requests.get(0).bodyText().contains("<v>aGVsbG8=</v>"));
    }
}
