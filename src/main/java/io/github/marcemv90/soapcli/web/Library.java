package io.github.marcemv90.soapcli.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.marcemv90.soapcli.core.ConfigException;
import io.github.marcemv90.soapcli.core.JsonSplice;
import io.github.marcemv90.soapcli.core.SecretMasker;
import io.github.marcemv90.soapcli.web.ApiModel.LibraryResponse;
import io.github.marcemv90.soapcli.web.ApiModel.SaveSavedRequest;
import io.github.marcemv90.soapcli.web.ApiModel.SaveSavedResponse;
import io.github.marcemv90.soapcli.web.ApiModel.SavedRequestDetail;
import io.github.marcemv90.soapcli.web.ApiModel.SavedRequestRef;
import io.github.marcemv90.soapcli.web.ApiModel.SavedRequestSummary;
import io.github.marcemv90.soapcli.web.ApiModel.SendError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The saved requests of a workspace, shared by everyone who opens it. Each is a JSON file of its own in the
 * {@code requests} folder:
 *
 * <pre>
 * { "app": "soapcli", "version": 1, "name": "Get order", "folder": "Orders", "updatedAt": "2026-11-12T10:00:00Z",
 *   "draft": { "endpoint": "...", "body": "&lt;...&gt;", "headers": [...], "args": {...}, "attachments": [...], ... } }
 * </pre>
 *
 * One file per request means that two people saving different requests never touch each other's, that a request can be
 * copied, backed up or put under version control like any file, and that an administrator can drop one in by hand.
 * Saving over an existing request quotes its <em>revision</em>, like the profiles do, so that nobody's change is
 * silently overwritten.
 *
 * <p>What is saved is what the page keeps in the browser already: never a password or a token, and never the contents
 * of an attachment. Because this library is shared, the value of a header or a variable that looks like a credential
 * (see {@link SecretMasker#isSecretName}) is dropped too, with a warning. The body is stored as it is.
 */
final class Library {

    static final String FOLDER = "requests";
    /** Also the file name (without {@code .json}); nothing else is ever looked up on disk. */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{0,100}");
    static final int MAX_REQUESTS = 1000;
    static final int MAX_NAME = 120;
    static final int MAX_FOLDER = 80;
    /** A saved request is a small file; this is also what {@link ConfigFile} is willing to read. */
    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final String SUFFIX = ".json";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path dir;
    private final Supplier<String> notAllowed;

    /** @param notAllowed why the server does not let the UI change the library, or {@code null}; asked every time */
    Library(Path dir, Supplier<String> notAllowed) {
        this.dir = dir;
        this.notAllowed = notAllowed;
    }

    Path dir() {
        return dir;
    }

    /** Why the library cannot be changed from the UI, or {@code null} if it can. */
    String readOnlyReason() {
        String reason = notAllowed.get();
        if (reason != null) {
            return reason;
        }
        Path folder = dir;
        while (folder != null && !Files.exists(folder)) {
            folder = folder.getParent();
        }
        return folder != null && Files.isWritable(folder) ? null
                : "The saved requests cannot be written: " + dir + " is not writable (with Docker, mount the workspaces folder without :ro).";
    }

    private ConfigFile fileOf(String id) {
        return new ConfigFile(dir.resolve(id + SUFFIX), "saved request", notAllowed, ConfigFile.SHARED);
    }

    /** An id is a file name that is safe to open; "save" and "delete" are endpoints of the API, so they are not ids. */
    static boolean validId(String id) {
        return id != null && ID.matcher(id).matches() && !id.equals("save") && !id.equals("delete");
    }

    private static String checkId(String id) {
        if (!validId(id)) {
            throw new SendError(400, "config", "That is not the id of a saved request");
        }
        return id;
    }

    // ---- reading ----------------------------------------------------------------------------------------------------

    private List<Path> files() {
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (Stream<Path> list = Files.list(dir)) {
            List<Path> files = new ArrayList<>();
            list.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(SUFFIX) && validId(n.substring(0, n.length() - SUFFIX.length()))
                        && Files.isRegularFile(p);
            }).forEach(files::add);
            return files;
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not list the saved requests: " + e.getMessage());
        }
    }

    int count() {
        return files().size();
    }

    LibraryResponse list() {
        LibraryResponse response = new LibraryResponse();
        response.readOnlyReason = readOnlyReason();
        response.editable = response.readOnlyReason == null;
        for (Path file : files()) {
            String name = file.getFileName().toString();
            response.requests.add(summarize(name.substring(0, name.length() - SUFFIX.length()), file));
        }
        Comparator<SavedRequestSummary> byFolder = Comparator.comparing(s -> s.folder == null ? "" : s.folder.toLowerCase(Locale.ROOT));
        response.requests.sort(byFolder.thenComparing(s -> s.name.toLowerCase(Locale.ROOT)).thenComparing(s -> s.id));
        return response;
    }

    private SavedRequestSummary summarize(String id, Path file) {
        SavedRequestSummary summary = new SavedRequestSummary();
        summary.id = id;
        summary.name = id;
        try {
            ConfigFile.Snapshot snapshot = new ConfigFile(file, "saved request", notAllowed, ConfigFile.SHARED).snapshot();
            summary.revision = snapshot.revision;
            JsonNode root = parseRequest(snapshot.text);
            summary.name = text(root, "name", id);
            summary.folder = text(root, "folder", "");
            summary.updatedAt = text(root, "updatedAt", null);
            JsonNode draft = root.get("draft");
            summary.endpoint = text(draft, "endpoint", "");
            summary.soapAction = text(draft, "soapAction", "");
            summary.profile = text(draft, "profile", "");
        } catch (ConfigException | SendError e) {
            summary.error = e.getMessage();
        }
        return summary;
    }

    SavedRequestDetail get(String id) {
        checkId(id);
        ConfigFile file = fileOf(id);
        if (!file.exists()) {
            throw new SendError(404, "config", "There is no saved request '" + id + "' any more");
        }
        ConfigFile.Snapshot snapshot = file.snapshot();
        JsonNode root;
        try {
            root = parseRequest(snapshot.text);
        } catch (ConfigException e) {
            throw new SendError(400, "config", "The saved request '" + id + "' cannot be read: " + e.getMessage());
        }
        SavedRequestDetail detail = new SavedRequestDetail();
        detail.id = id;
        detail.name = text(root, "name", id);
        detail.folder = text(root, "folder", "");
        detail.updatedAt = text(root, "updatedAt", null);
        detail.revision = snapshot.revision;
        detail.draft = root.get("draft");
        return detail;
    }

    /** The document of a saved request, checked: an object with a {@code draft} object that has a text {@code body}. */
    private static JsonNode parseRequest(String text) {
        JsonNode root = text.trim().isEmpty() ? null : JsonSplice.parse(text);
        if (root == null || !root.isObject()) {
            throw new ConfigException("it is not a JSON object");
        }
        JsonNode draft = root.get("draft");
        if (draft == null || !draft.isObject() || !draft.path("body").isTextual()) {
            throw new ConfigException("it has no \"draft\" with a \"body\"");
        }
        return root;
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    // ---- writing ----------------------------------------------------------------------------------------------------

    /** Saves a new request (no id) or replaces the one the id names, if it is still at the revision the page has. */
    SaveSavedResponse save(SaveSavedRequest request) {
        if (request == null) {
            throw new SendError(400, "config", "The request to save is empty");
        }
        String name = clean(request.name, MAX_NAME, "The name");
        if (name.isEmpty()) {
            throw new SendError(400, "config", "Give the request a name");
        }
        String folder = clean(request.folder, MAX_FOLDER, "The folder");
        if (request.draft == null || !request.draft.isObject() || !request.draft.path("body").isTextual()) {
            throw new SendError(400, "config", "There is no request to save (it needs a body)");
        }
        SaveSavedResponse response = new SaveSavedResponse();
        ObjectNode draft = sanitize(request.draft, response.warnings);

        response.updatedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        ObjectNode document = JSON.createObjectNode();
        document.put("app", "soapcli");
        document.put("version", 1);
        document.put("name", name);
        document.put("folder", folder);
        document.put("updatedAt", response.updatedAt);
        document.set("draft", draft);
        String text;
        try {
            text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not write the request: " + e.getMessage());
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new SendError(413, "too-large", "A saved request can be at most " + (MAX_BYTES / (1024 * 1024)) + " MB");
        }

        synchronized (this) {
            if (request.id == null || request.id.isBlank()) {
                if (count() >= MAX_REQUESTS) {
                    throw new SendError(409, "conflict", "This workspace already has " + MAX_REQUESTS + " saved requests: remove some first");
                }
                for (int attempt = 1; ; attempt++) {
                    String id = newId(name);
                    try {
                        response.id = id;
                        response.revision = fileOf(id).edit(ConfigFile.MISSING, current -> text);
                        return response;
                    } catch (SendError e) {
                        if (e.httpStatus != 409 || attempt >= 5) {
                            throw e; // 409: that id is taken (by someone else, a moment ago): another one is tried
                        }
                    }
                }
            }
            checkId(request.id);
            if (request.revision == null || request.revision.isBlank()) {
                throw new SendError(400, "config", "Say which version of the saved request you are changing (its revision)");
            }
            ConfigFile file = fileOf(request.id);
            if (!file.exists()) {
                throw new SendError(404, "config", "The saved request '" + request.id + "' does not exist any more");
            }
            response.id = request.id;
            response.revision = file.edit(request.revision, current -> text);
            return response;
        }
    }

    void delete(SavedRequestRef ref) {
        if (ref == null) {
            throw new SendError(400, "config", "Say which saved request to remove");
        }
        checkId(ref.id);
        synchronized (this) {
            String reason = readOnlyReason();
            if (reason != null) {
                throw new SendError(403, "forbidden", reason);
            }
            ConfigFile file = fileOf(ref.id);
            if (!file.exists()) {
                throw new SendError(404, "config", "The saved request '" + ref.id + "' does not exist any more");
            }
            if (!file.revision().equals(ref.revision)) {
                throw new SendError(409, "conflict", "The saved request changed since you opened it (in another window, or by hand). "
                        + "Reload it and try again.");
            }
            try {
                Files.delete(file.path());
            } catch (IOException e) {
                throw new SendError(500, "internal", "Could not remove the saved request: " + e.getMessage());
            }
        }
    }

    // ---- what is kept -----------------------------------------------------------------------------------------------

    /**
     * Keeps from the page's draft the fields the page itself stores, and no credential: not the password or token of the
     * Auth tab, not the value of a header or a variable whose name says it is one.
     */
    static ObjectNode sanitize(JsonNode draft, List<String> warnings) {
        ObjectNode out = JSON.createObjectNode();
        for (String key : new String[]{"endpoint", "body", "soapVersion", "soapAction", "profile"}) {
            if (draft.path(key).isTextual()) {
                out.put(key, draft.get(key).asText());
            }
        }
        if (draft.path("mtom").isBoolean()) {
            out.put("mtom", draft.get("mtom").asBoolean());
        }
        JsonNode options = draft.get("options");
        if (options != null && options.isObject()) {
            ObjectNode o = out.putObject("options");
            if (options.path("timeoutSeconds").isNumber()) {
                o.put("timeoutSeconds", options.get("timeoutSeconds").asInt());
            }
            for (String flag : new String[]{"followRedirects", "insecure"}) {
                if (options.path(flag).isBoolean()) {
                    o.put(flag, options.get(flag).asBoolean());
                }
            }
        }
        JsonNode auth = draft.get("auth");
        if (auth != null && auth.isObject()) {
            ObjectNode a = out.putObject("auth");
            String kind = text(auth, "kind", "none");
            a.put("kind", kind.equals("basic") || kind.equals("bearer") ? kind : "none");
            a.put("user", text(auth, "user", ""));
            if (!kind.equals("none") && (auth.has("password") || auth.has("token"))) {
                warnings.add("The password or token of the Auth tab was not saved: a request in the library is shared. "
                        + "Use a profile for credentials.");
            }
        }
        JsonNode headers = draft.get("headers");
        if (headers != null && headers.isArray()) {
            ArrayNode list = out.putArray("headers");
            for (JsonNode h : headers) {
                String headerName = text(h, "name", "").trim();
                if (headerName.isEmpty()) {
                    continue;
                }
                ObjectNode copy = list.addObject();
                copy.put("id", text(h, "id", ""));
                copy.put("name", headerName);
                boolean secret = SecretMasker.isSecretName(headerName) && !text(h, "value", "").isEmpty();
                copy.put("value", secret ? "" : text(h, "value", ""));
                copy.put("enabled", !h.has("enabled") || h.get("enabled").asBoolean(true));
                if (secret) {
                    warnings.add("The value of the header '" + headerName + "' was not saved: it looks like a credential, "
                            + "and a request in the library is shared. Use a profile for credentials.");
                }
            }
        }
        JsonNode args = draft.get("args");
        if (args != null && args.isObject()) {
            ObjectNode map = out.putObject("args");
            Iterator<Map.Entry<String, JsonNode>> it = args.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isTextual()) {
                    continue;
                }
                if (SecretMasker.isSecretName(e.getKey()) && !e.getValue().asText().isEmpty()) {
                    warnings.add("The value of the variable '" + e.getKey() + "' was not saved: it looks like a credential, "
                            + "and a request in the library is shared.");
                } else {
                    map.put(e.getKey(), e.getValue().asText());
                }
            }
        }
        JsonNode attachments = draft.get("attachments");
        if (attachments != null && attachments.isArray()) {
            ArrayNode list = out.putArray("attachments");
            for (JsonNode a : attachments) {
                String cid = text(a, "cid", "");
                String path = text(a, "path", "");
                if (!cid.isEmpty() && !path.trim().isEmpty()) {
                    list.addObject().put("cid", cid).put("path", path);
                }
            }
        }
        return out;
    }

    /** Trimmed text without control characters, at most {@code max} long. */
    private static String clean(String value, int max, String what) {
        String text = value == null ? "" : value.trim();
        if (text.length() > max) {
            throw new SendError(400, "config", what + " can have at most " + max + " characters");
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                throw new SendError(400, "config", what + " cannot contain control characters");
            }
        }
        return text;
    }

    /** {@code get-order-k3f9a}: readable in a folder listing, and unlikely to be taken. */
    private static String newId(String name) {
        String slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 40) {
            slug = slug.substring(0, 40).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            slug = "request";
        }
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            suffix.append(Character.forDigit(RANDOM.nextInt(36), 36));
        }
        return slug + "-" + suffix;
    }
}
