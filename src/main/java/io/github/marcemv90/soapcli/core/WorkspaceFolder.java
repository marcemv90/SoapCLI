package io.github.marcemv90.soapcli.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The folder of a workspace, as far as the command line and the web server both need to know it: what it is called, where
 * its profiles are, and the secrets that sit next to them. (The web server adds saved requests, attachments and the rest.)
 *
 * <pre>
 * workspaces/            what --workspaces-dir / SOAPCLI_WORKSPACES_DIR points at
 *   billing/             a workspace
 *     profiles.json      its profiles; relative paths inside them start in this folder
 *     secrets.env        optional NAME=value lines that its profiles may refer to as ${NAME}
 * </pre>
 */
public final class WorkspaceFolder {

    /** A workspace is a folder with such a name; the name ends up in URLs and file names, so nothing else is accepted. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._\\-]{0,63}");
    public static final String PROFILES_FILE = "profiles.json";
    public static final String SECRETS_FILE = "secrets.env";
    private static final long MAX_SECRETS_BYTES = 1024 * 1024;

    private WorkspaceFolder() {
    }

    /**
     * The folder of the workspace {@code name} inside {@code workspacesDir}.
     *
     * @throws ConfigException if the name is not a valid workspace name, or there is no such workspace
     */
    public static Path find(Path workspacesDir, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new ConfigException("'" + name + "' is not a workspace name (letters, digits, '.', '_' and '-', "
                    + "starting with a letter or a digit)");
        }
        if (!Files.isDirectory(workspacesDir)) {
            throw new ConfigException("The workspaces folder '" + workspacesDir + "' does not exist");
        }
        Path folder = workspacesDir.resolve(name);
        if (!Files.isDirectory(folder)) {
            throw new ConfigException("There is no workspace '" + name + "' in '" + workspacesDir + "'");
        }
        return folder;
    }

    public static Path profilesFile(Path workspace) {
        return workspace.resolve(PROFILES_FILE);
    }

    /**
     * {@code base} (the environment of the process) with the variables of the workspace's {@code secrets.env} over it, which
     * is what {@code ${NAME}} in its profiles is read from. The file is optional; one that cannot be read adds nothing.
     */
    public static Map<String, String> env(Map<String, String> base, Path workspace) {
        Map<String, String> merged = new HashMap<>(base);
        Path file = workspace.resolve(SECRETS_FILE);
        try {
            if (Files.isRegularFile(file) && Files.size(file) <= MAX_SECRETS_BYTES) {
                merged.putAll(DotEnv.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
            }
        } catch (IOException e) {
            // unreadable: its variables stay unset, which the checks of a profile report
        }
        return merged;
    }
}
