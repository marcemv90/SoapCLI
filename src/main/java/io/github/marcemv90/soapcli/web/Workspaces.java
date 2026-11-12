package io.github.marcemv90.soapcli.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.marcemv90.soapcli.web.ApiModel.SendError;
import io.github.marcemv90.soapcli.web.ApiModel.WorkspacesResponse;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The workspaces of a server: the sub-folders of {@code --workspaces-dir}. Every folder whose name is a valid workspace
 * name is one; the rest of the directory (a {@code .git} folder, a stray file) is ignored. The list is read from the
 * disk each time it is asked for, so a workspace that someone adds or removes by hand shows up without a restart.
 *
 * <p>Without {@code --workspaces-dir} there is exactly one, {@link Workspace#single}, and these methods say so.
 */
final class Workspaces {

    private final ServeOptions options;
    private final Workspace single;
    /** One object per workspace, so that the lock that serialises the edits of its files is shared by all requests. */
    private final Map<String, Workspace> cache = new ConcurrentHashMap<>();

    Workspaces(ServeOptions options) {
        this.options = options;
        this.single = options.workspacesEnabled() ? null : Workspace.single(options);
    }

    boolean enabled() {
        return options.workspacesEnabled();
    }

    /** The one workspace of a server without workspaces; {@code null} when it has them. */
    Workspace single() {
        return single;
    }

    Path dir() {
        return options.workspacesDir;
    }

    /** The names of the workspaces, in alphabetical order. */
    List<String> names() {
        List<String> names = new ArrayList<>();
        if (!enabled() || !Files.isDirectory(dir())) {
            return names;
        }
        try (Stream<Path> list = Files.list(dir())) {
            list.map(p -> p.getFileName().toString())
                    .filter(n -> Workspace.NAME.matcher(n).matches() && Files.isDirectory(dir().resolve(n)))
                    .forEach(names::add);
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not list the workspaces in " + dir() + ": " + e.getMessage());
        }
        names.sort(String.CASE_INSENSITIVE_ORDER.thenComparing(java.util.Comparator.naturalOrder()));
        return names;
    }

    /** The workspace called {@code name}; 404 if there is none. Nothing but a valid name ever reaches the disk. */
    Workspace get(String name) {
        if (!enabled()) {
            throw new SendError(404, "config", "This server does not use workspaces");
        }
        if (name == null || !Workspace.NAME.matcher(name).matches() || !Files.isDirectory(dir().resolve(name))) {
            if (name != null) {
                cache.remove(name);
            }
            throw new SendError(404, "config", "There is no workspace '" + name + "'");
        }
        return cache.computeIfAbsent(name, n -> Workspace.in(options, n, dir().resolve(n)));
    }

    WorkspacesResponse list() {
        WorkspacesResponse response = new WorkspacesResponse();
        response.enabled = enabled();
        if (!enabled()) {
            return response;
        }
        response.dir = dir().toString();
        response.manageReason = manageReason();
        response.manageable = response.manageReason == null;
        for (String name : names()) {
            response.workspaces.add(get(name).summary());
        }
        return response;
    }

    // ---- creating and removing --------------------------------------------------------------------------------------

    /** Why the UI may not create or remove workspaces, or {@code null} if it may. */
    String manageReason() {
        if (!enabled()) {
            return "This server does not use workspaces (start it with --workspaces-dir).";
        }
        String off = options.configEditOffReason();
        if (off != null) {
            return off;
        }
        return Files.isWritable(dir()) ? null
                : "The workspaces folder (" + dir() + ") is not writable. With Docker, mount it without :ro.";
    }

    private void requireManageable() {
        String reason = manageReason();
        if (reason != null) {
            throw new SendError(403, "forbidden", reason);
        }
    }

    /** Creates the folder of a workspace, with its {@code attachments} and {@code requests} folders and a {@code workspace.json}. */
    Workspace create(String name, String description) {
        requireManageable();
        if (name == null || !Workspace.NAME.matcher(name).matches()) {
            throw new SendError(400, "config", "A workspace name is made of letters, digits, '.', '_' and '-' (at most 64) "
                    + "and starts with a letter or a digit");
        }
        String text = description == null ? "" : description.trim();
        if (text.length() > Workspace.MAX_DESCRIPTION) {
            throw new SendError(400, "config", "The description is longer than " + Workspace.MAX_DESCRIPTION + " characters");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new SendError(400, "config", "The description cannot contain control characters");
        }
        Path folder = dir().resolve(name);
        try {
            Files.createDirectory(folder);
        } catch (FileAlreadyExistsException e) {
            throw new SendError(409, "conflict", "A workspace called '" + name + "' already exists");
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not create the workspace: " + e.getMessage());
        }
        try {
            Files.createDirectory(folder.resolve(Workspace.ATTACHMENTS_FOLDER));
            Files.createDirectory(folder.resolve(Library.FOLDER));
            ObjectMapper json = new ObjectMapper();
            ObjectNode meta = json.createObjectNode();
            meta.put("description", text);
            meta.put("readOnly", false);
            meta.putArray("allowedHosts");
            String content = json.writerWithDefaultPrettyPrinter().writeValueAsString(meta) + "\n";
            new ConfigFile(folder.resolve(Workspace.META_FILE), "workspace settings", () -> null, ConfigFile.SHARED)
                    .edit(ConfigFile.MISSING, current -> content);
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not set up the workspace: " + e.getMessage());
        }
        return get(name);
    }

    /**
     * Takes a workspace out of use by moving its folder to {@code .removed-<name>-<time>}, in the same directory. Nothing
     * is deleted: the saved requests, attachments and profiles are all still there for whoever wants them back, and a
     * folder with that name is not a workspace, so it no longer shows up.
     *
     * @return the new name of the folder
     */
    String remove(String name) {
        requireManageable();
        Workspace workspace = get(name);
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = dir().resolve(".removed-" + name + "-" + stamp);
        for (int i = 2; Files.exists(target); i++) {
            target = dir().resolve(".removed-" + name + "-" + stamp + "-" + i);
        }
        try {
            Files.move(workspace.dir, target);
        } catch (IOException e) {
            throw new SendError(500, "internal", "Could not remove the workspace: " + e.getMessage());
        }
        cache.remove(name);
        return target.getFileName().toString();
    }
}
