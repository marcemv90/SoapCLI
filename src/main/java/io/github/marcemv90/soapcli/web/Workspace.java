package io.github.marcemv90.soapcli.web;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.marcemv90.soapcli.core.ConfigException;
import io.github.marcemv90.soapcli.core.HostPolicy;
import io.github.marcemv90.soapcli.core.JsonSplice;
import io.github.marcemv90.soapcli.core.Profiles;
import io.github.marcemv90.soapcli.core.WorkspaceFolder;
import io.github.marcemv90.soapcli.web.ApiModel.WorkspaceSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A workspace: a folder that holds everything one team or one project needs, so that it can be copied, backed up and
 * shared as a whole.
 *
 * <pre>
 * billing/
 *   workspace.json       optional: { "description": "...", "readOnly": false, "allowedHosts": ["*.billing.example.com"] }
 *   profiles.json        the profiles (same format as the command line's file)
 *   placeholders.json    the options offered for placeholders
 *   secrets.env          optional: NAME=value lines that the profiles may refer to as ${NAME}; the UI never touches it
 *   attachments/         the only folder files can be attached from, by path
 *   requests/            the saved requests of the library, one JSON file each
 *   certs/               by convention, where keystores go (a relative path of a profile starts in this folder)
 * </pre>
 *
 * A server that does not use workspaces has exactly one, made of the files that {@code --profile-file},
 * {@code --placeholders-file} and {@code --attachments-dir} name, so that the code does not care which mode it is in;
 * it has no folder, no library, and its relative paths start in the working directory, as they always did.
 */
final class Workspace {

    static final String META_FILE = "workspace.json";
    static final String SECRETS_FILE = WorkspaceFolder.SECRETS_FILE;
    static final String PROFILES_FILE = WorkspaceFolder.PROFILES_FILE;
    static final String PLACEHOLDERS_FILE = "placeholders.json";
    static final String ATTACHMENTS_FOLDER = "attachments";
    /** A workspace is a folder with such a name: the name is used in URLs and file names, so nothing else is accepted. */
    static final Pattern NAME = WorkspaceFolder.NAME;
    static final int MAX_DESCRIPTION = 300;
    private static final long MAX_SMALL_FILE = 1024 * 1024;

    /** What {@code workspace.json} says. */
    static final class Meta {
        String description = "";
        boolean readOnly;
        final List<String> allowedHosts = new ArrayList<>();
        /** A problem reading the file. The workspace is then treated as read-only and nothing is sent from it. */
        String error;
    }

    private final ServeOptions options;
    final String name;
    /** The folder, or {@code null} for the single set of files of a server without workspaces. */
    final Path dir;
    final ConfigFile profiles;
    final ConfigFile placeholders;
    /** The saved requests; {@code null} without workspaces (the page keeps them in the browser then). */
    final Library library;
    private final Path attachmentsDir;

    private Workspace(ServeOptions options, String name, Path dir, ConfigFile profiles, ConfigFile placeholders,
                      Library library, Path attachmentsDir) {
        this.options = options;
        this.name = name;
        this.dir = dir;
        this.profiles = profiles;
        this.placeholders = placeholders;
        this.library = library;
        this.attachmentsDir = attachmentsDir;
    }

    /** The one set of files of a server that does not use workspaces. */
    static Workspace single(ServeOptions options) {
        String editOff = options.configEditOffReason();
        return new Workspace(options, null, null,
                new ConfigFile(options.profileFile, "profiles file", editOff),
                new ConfigFile(options.placeholdersFile, "placeholders file", editOff),
                null, options.attachmentsDir);
    }

    /** The workspace in {@code dir}. */
    static Workspace in(ServeOptions options, String name, Path dir) {
        // Asked every time: an administrator can mark the workspace read-only by hand while the server runs.
        java.util.function.Supplier<String> configReason = () -> firstNonNull(options.configEditOffReason(), readOnlyReason(dir));
        java.util.function.Supplier<String> libraryReason = () -> firstNonNull(options.libraryEditOffReason(), readOnlyReason(dir));
        return new Workspace(options, name, dir,
                new ConfigFile(dir.resolve(PROFILES_FILE), "profiles file", configReason, ConfigFile.PRIVATE),
                new ConfigFile(dir.resolve(PLACEHOLDERS_FILE), "placeholders file", configReason, ConfigFile.PRIVATE),
                new Library(dir.resolve(Library.FOLDER), libraryReason),
                dir.resolve(ATTACHMENTS_FOLDER));
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }

    boolean isSingle() {
        return dir == null;
    }

    /** The folder files may be attached from, by path. */
    Path attachmentsDir() {
        return attachmentsDir;
    }

    /** Relative paths of the profiles start here; {@code null} means the working directory. */
    Path baseDir() {
        return dir;
    }

    // ---- workspace.json ---------------------------------------------------------------------------------------------

    Meta meta() {
        return readMeta(name, dir);
    }

    /** Reads {@code workspace.json} of the workspace {@code name} in {@code dir} ({@code null}: a server without workspaces). */
    static Meta readMeta(String name, Path dir) {
        Meta meta = new Meta();
        if (dir == null) {
            return meta;
        }
        Path file = dir.resolve(META_FILE);
        if (!Files.isRegularFile(file)) {
            return meta;
        }
        try {
            if (Files.size(file) > MAX_SMALL_FILE) {
                throw new ConfigException("it is larger than 1 MB");
            }
            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonNode root = text.trim().isEmpty() ? null : JsonSplice.parse(text);
            if (root == null) {
                return meta;
            }
            if (!root.isObject()) {
                throw new ConfigException("it must be a JSON object");
            }
            JsonNode description = root.get("description");
            if (description != null && description.isValueNode() && !description.isNull()) {
                meta.description = description.asText();
            }
            JsonNode readOnly = root.get("readOnly");
            if (readOnly != null && !readOnly.isNull()) {
                if (!readOnly.isBoolean()) {
                    throw new ConfigException("\"readOnly\" must be true or false");
                }
                meta.readOnly = readOnly.asBoolean();
            }
            JsonNode hosts = root.get("allowedHosts");
            if (hosts != null && !hosts.isNull()) {
                if (!hosts.isArray()) {
                    throw new ConfigException("\"allowedHosts\" must be a list of host patterns");
                }
                for (JsonNode h : hosts) {
                    if (!h.isTextual()) {
                        throw new ConfigException("\"allowedHosts\" must be a list of host patterns");
                    }
                    if (!h.asText().isBlank()) {
                        meta.allowedHosts.add(h.asText().trim());
                    }
                }
            }
        } catch (ConfigException | IOException e) {
            meta.error = "The settings of workspace '" + name + "' (" + META_FILE + ") cannot be read: " + e.getMessage();
        }
        return meta;
    }

    /** Why the UI may not change this workspace, whatever the server allows; {@code null} if it may. */
    String readOnlyReason() {
        return readOnlyReason(dir);
    }

    private static String readOnlyReason(Path dir) {
        Meta meta = readMeta(dir.getFileName().toString(), dir);
        if (meta.error != null) {
            return meta.error + " Until it is fixed the workspace is read-only.";
        }
        return meta.readOnly ? "The workspace is read-only: its " + META_FILE + " says \"readOnly\": true." : null;
    }

    /** The destinations this workspace may call: what the server allows, narrowed by the workspace's own list. */
    HostPolicy hostPolicy(HostPolicy server) {
        List<String> own = meta().allowedHosts;
        if (own.isEmpty()) {
            return server;
        }
        HostPolicy narrow = new HostPolicy(own);
        return server == null ? narrow : server.and(narrow);
    }

    // ---- secrets ----------------------------------------------------------------------------------------------------

    /**
     * What {@code ${NAME}} in a profile is read from: the server's environment, over which the workspace's
     * {@code secrets.env} takes precedence. Read on every use, so a change by hand applies at once.
     */
    Map<String, String> env() {
        return dir == null ? new HashMap<>(options.env) : WorkspaceFolder.env(options.env, dir);
    }

    // ---- summary ----------------------------------------------------------------------------------------------------

    WorkspaceSummary summary() {
        WorkspaceSummary summary = new WorkspaceSummary();
        summary.name = name;
        Meta meta = meta();
        summary.description = meta.description;
        summary.readOnly = meta.readOnly || meta.error != null;
        summary.allowedHosts.addAll(meta.allowedHosts);
        summary.error = meta.error;
        try {
            if (profiles.exists()) {
                summary.profiles = Profiles.parse(profiles.read(), profiles.path()).names().size();
            } else {
                summary.profiles = 0;
            }
        } catch (RuntimeException e) {
            summary.profiles = null; // the file has a problem: the Profiles page says which
        }
        try {
            summary.placeholders = placeholders.exists() ? PlaceholderOptions.parse(placeholders.read()).size() : 0;
        } catch (RuntimeException e) {
            summary.placeholders = null;
        }
        if (library != null) {
            summary.requests = library.count();
        }
        return summary;
    }
}
