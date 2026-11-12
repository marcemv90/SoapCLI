package io.github.marcemv90.soapcli.web;

import io.github.marcemv90.soapcli.core.ConfigException;
import io.github.marcemv90.soapcli.core.Profiles;
import io.github.marcemv90.soapcli.core.Version;
import java.io.IOException;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** The {@code serve} and {@code healthcheck} subcommands: start the web UI, and ask whether one is alive. */
public final class ServeCommand {

    private ServeCommand() {
    }

    /** Starts the UI and blocks until the process is stopped. Returns only when it could not start. */
    public static int run(String[] args, Map<String, String> env, String userHome, PrintStream out, PrintStream err) {
        ServeOptions options;
        try {
            options = ServeOptions.parse(args, env, userHome);
        } catch (ConfigException e) {
            err.println("Error: " + e.getMessage());
            err.println("Run with 'serve --help' to see the available options.");
            return 1;
        }
        if (options.help) {
            out.print(ServeOptions.usage());
            return 0;
        }

        if (options.workspacesEnabled()) {
            if (!Files.isDirectory(options.workspacesDir)) {
                err.println("Error: the workspaces folder '" + options.workspacesDir + "' is not a directory "
                        + "(--workspaces-dir / SOAPCLI_WORKSPACES_DIR). Create it first: each folder inside it is a workspace");
                return 1;
            }
        } else if (!Files.isDirectory(options.attachmentsDir)) {
            err.println("Error: the attachments folder '" + options.attachmentsDir + "' is not a directory "
                    + "(--attachments-dir / SOAPCLI_ATTACHMENTS_DIR)");
            return 1;
        }

        WebServer server = new WebServer(options, out);
        try {
            server.start();
        } catch (IOException e) {
            err.println("Error: could not listen on " + options.host + ":" + options.port + ": " + e.getMessage());
            return 1;
        }

        printBanner(options, server.port(), out);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        try {
            Thread.currentThread().join(); // serve until the process is stopped
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return 0;
    }

    private static void printBanner(ServeOptions o, int port, PrintStream out) {
        out.println("SoapCLI " + Version.get() + " web UI");
        out.println("  Listening on http://" + o.host + ":" + port);
        if (o.workspacesEnabled()) {
            printWorkspaces(o, out);
        } else {
            printSingleSet(o, out);
        }
        out.println("  Allowed destinations: " + (o.allowedHosts.isEmpty() ? "any host" : String.join(", ", o.allowedHosts)));
        out.println("  UI login: " + (o.hasAuth() ? "required (user '" + o.uiUser + "')" : "NOT required"));
        if (!o.isLoopbackOnly() && !o.hasAuth()) {
            out.println();
            out.println("WARNING: the UI is reachable from the network and has no login. Anyone who can reach it can");
            out.println("         send requests from this machine, with the credentials of the profiles it holds.");
            out.println("         Set --ui-user / --ui-password, restrict destinations with --allow-host, and publish the");
            out.println("         port only where you mean to (for Docker: -p 127.0.0.1:8080:8080).");
        }
        if (!o.isLoopbackOnly() && o.configEditAllowed()) {
            out.println();
            out.println("WARNING: the UI may change the profiles file, so anyone who can open it can point a profile at another");
            out.println("         destination, or at any file of this machine. Keep it for UIs with a login (--ui-user).");
        }
        if (!o.workspacesEnabled() && !o.isLoopbackOnly() && !o.attachmentsDirExplicit) {
            out.println();
            out.println("WARNING: users of the UI can attach, and so send to the destinations they may call, any file under");
            out.println("         " + o.attachmentsDir + ". Choose a folder meant for that with --attachments-dir.");
        }
        out.flush();
    }

    /** The files of a server without workspaces: one profiles file, one placeholders file, one attachments folder. */
    private static void printSingleSet(ServeOptions o, PrintStream out) {
        if (Files.isRegularFile(o.profileFile)) {
            String count;
            try {
                count = Profiles.load(o.profileFile).names().size() + " profile(s)";
            } catch (ConfigException e) {
                count = "unreadable: " + e.getMessage();
            }
            out.println("  Profiles: " + o.profileFile + " (" + count + ")");
        } else {
            out.println("  Profiles: none (no file at " + o.profileFile + ")");
        }
        out.println("  Placeholder options: " + (Files.isRegularFile(o.placeholdersFile) ? o.placeholdersFile : "none (no file at " + o.placeholdersFile + ")"));
        out.println("  Editing profiles and placeholders from the UI: " + (o.configEditAllowed() ? "ALLOWED" : "off")
                + (o.allowConfigEdit == null && o.configEditAllowed() ? " (this server listens on this machine only)" : ""));
        if (o.configEditAllowed()) {
            // Allowed does not mean possible: a volume mounted read-only, say, cannot be edited whatever the server permits.
            for (ConfigFile f : new ConfigFile[]{new ConfigFile(o.profileFile, "profiles file", null),
                    new ConfigFile(o.placeholdersFile, "placeholders file", null)}) {
                String why = f.readOnlyReason();
                if (why != null) {
                    out.println("    but: " + why);
                }
            }
        }
        out.println("  Attachments by path: files under " + o.attachmentsDir
                + (o.attachmentsDirExplicit ? "" : " (the working directory; change it with --attachments-dir)"));
    }

    /** What a server with workspaces keeps, and where. */
    private static void printWorkspaces(ServeOptions o, PrintStream out) {
        Workspaces workspaces = new Workspaces(o);
        List<String> names = workspaces.names();
        out.println("  Workspaces: " + o.workspacesDir + " (" + (names.isEmpty() ? "none yet" : names.size() + ": " + String.join(", ", names)) + ")");
        out.println("    Each has its own profiles.json, placeholders.json, attachments/ and requests/ (saved requests).");
        out.println("    Create one from the UI" + (workspaces.manageReason() == null ? "" : " (off: " + workspaces.manageReason() + ")")
                + ", or by making a folder in " + o.workspacesDir + ".");
        out.println("  Editing profiles and placeholders from the UI: " + (o.configEditAllowed() ? "ALLOWED" : "off")
                + (o.allowConfigEdit == null && o.configEditAllowed() ? " (this server listens on this machine only)" : ""));
        out.println("  Saving requests to the workspaces from the UI: " + (o.libraryEditAllowed() ? "ALLOWED" : "off"));
        if (!Files.isWritable(o.workspacesDir)) {
            out.println("    but: " + o.workspacesDir + " is not writable, so nothing can be saved there (with Docker, mount it without :ro).");
        }
    }

    /** Exit code 0 if a UI answers {@code /healthz} on this machine's port. Used by the Docker HEALTHCHECK. */
    public static int healthcheck(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        int port = 8080;
        try {
            String fromEnv = env.get("SOAPCLI_PORT");
            if (fromEnv != null && !fromEnv.isBlank()) {
                port = Integer.parseInt(fromEnv.trim());
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--port") && i + 1 < args.length) {
                    port = Integer.parseInt(args[++i].trim());
                }
            }
        } catch (NumberFormatException e) {
            err.println("Error: the port is not a number");
            return 1;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/healthz")
                    .openConnection(Proxy.NO_PROXY);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int status = conn.getResponseCode();
            conn.disconnect();
            if (status == 200) {
                out.println("ok");
                return 0;
            }
            err.println("unhealthy: HTTP " + status);
            return 1;
        } catch (IOException e) {
            err.println("unhealthy: " + e.getMessage());
            return 1;
        }
    }
}
