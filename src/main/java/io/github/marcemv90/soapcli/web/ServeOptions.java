package io.github.marcemv90.soapcli.web;

import io.github.marcemv90.soapcli.core.ConfigException;
import io.github.marcemv90.soapcli.core.Profiles;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Settings of the web UI. Every option has an environment variable too, so a container can be configured without
 * touching the command line.
 */
public final class ServeOptions {

    public String host = "127.0.0.1";
    public int port = 8080;
    public String uiUser;
    public String uiPassword;
    public final List<String> allowedHosts = new ArrayList<>();
    public Path profileFile;
    /** The file with the options offered for the placeholders of a request; see {@code PlaceholderOptions}. */
    public Path placeholdersFile;
    /**
     * Whether the UI may change the profiles and placeholders files: {@code true} / {@code false} when chosen, or
     * {@code null} for the default, which is "yes, but only when the server listens on this machine only".
     */
    public Boolean allowConfigEdit;
    /**
     * The only folder the UI may read attachments from when a request names a file by path. Defaults to the working
     * directory (in the Docker image, {@code /work}).
     */
    public Path attachmentsDir;
    /** Whether {@link #attachmentsDir} was chosen, as opposed to being the working directory by default. */
    public boolean attachmentsDirExplicit;
    public long maxBodyBytes = 25L * 1024 * 1024;
    public long maxResponseBytes = 20L * 1024 * 1024;
    public boolean noInsecureTls;
    public boolean help;

    /** Environment used to expand {@code ${VAR}} in profiles (and to find the profiles file). */
    public Map<String, String> env;
    public String userHome;

    public boolean hasAuth() {
        return uiUser != null && uiPassword != null;
    }

    /** Whether the UI may change the profiles and placeholders files. */
    public boolean configEditAllowed() {
        return allowConfigEdit != null ? allowConfigEdit : isLoopbackOnly();
    }

    /** Why the UI may not change the configuration files, or {@code null} if it may. */
    public String configEditOffReason() {
        if (configEditAllowed()) {
            return null;
        }
        return allowConfigEdit != null
                ? "Editing from the UI was switched off on this server (--no-config-edit)."
                : "Editing from the UI is off because this server listens on a network address. "
                + "To turn it on, start it with --allow-config-edit (and set a login).";
    }

    /** {@code SOAPCLI_PLACEHOLDERS_FILE}, else {@code ~/.config/soap-cli-placeholders.json}. */
    public static Path defaultPlaceholdersFile(Map<String, String> env, String userHome) {
        String fromEnv = env.get("SOAPCLI_PLACEHOLDERS_FILE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Paths.get(fromEnv.trim());
        }
        if (userHome != null && !userHome.isEmpty()) {
            return Paths.get(userHome, ".config", "soap-cli-placeholders.json");
        }
        return Paths.get("soap-cli-placeholders.json");
    }

    public boolean isLoopbackOnly() {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]");
    }

    public static ServeOptions parse(String[] args, Map<String, String> env, String userHome) {
        ServeOptions o = new ServeOptions();
        o.env = env;
        o.userHome = userHome;

        // Defaults from the environment, overridden by the command line.
        if (notBlank(env.get("SOAPCLI_HOST"))) {
            o.host = env.get("SOAPCLI_HOST").trim();
        }
        if (notBlank(env.get("SOAPCLI_PORT"))) {
            o.port = integer(env.get("SOAPCLI_PORT"), "SOAPCLI_PORT");
        }
        o.uiUser = blankToNull(env.get("SOAPCLI_UI_USER"));
        o.uiPassword = blankToNull(env.get("SOAPCLI_UI_PASSWORD"));
        if (notBlank(env.get("SOAPCLI_ALLOWED_HOSTS"))) {
            for (String h : env.get("SOAPCLI_ALLOWED_HOSTS").split(",")) {
                if (notBlank(h)) {
                    o.allowedHosts.add(h.trim());
                }
            }
        }
        if (notBlank(env.get("SOAPCLI_MAX_BODY_MB"))) {
            o.maxBodyBytes = megabytes(env.get("SOAPCLI_MAX_BODY_MB"), "SOAPCLI_MAX_BODY_MB");
        }
        if (notBlank(env.get("SOAPCLI_MAX_RESPONSE_MB"))) {
            o.maxResponseBytes = megabytes(env.get("SOAPCLI_MAX_RESPONSE_MB"), "SOAPCLI_MAX_RESPONSE_MB");
        }
        o.noInsecureTls = isTrue(env.get("SOAPCLI_NO_INSECURE_TLS"));
        o.profileFile = Profiles.defaultFile(env, userHome);
        o.placeholdersFile = defaultPlaceholdersFile(env, userHome);
        if (notBlank(env.get("SOAPCLI_ALLOW_CONFIG_EDIT"))) {
            o.allowConfigEdit = isTrue(env.get("SOAPCLI_ALLOW_CONFIG_EDIT"));
        }
        o.attachmentsDir = Paths.get("").toAbsolutePath().normalize();
        if (notBlank(env.get("SOAPCLI_ATTACHMENTS_DIR"))) {
            o.attachmentsDir = Paths.get(env.get("SOAPCLI_ATTACHMENTS_DIR").trim()).toAbsolutePath().normalize();
            o.attachmentsDirExplicit = true;
        }

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String inline = null;
            int eq = arg.startsWith("--") ? arg.indexOf('=') : -1;
            if (eq > 2) {
                inline = arg.substring(eq + 1);
                arg = arg.substring(0, eq);
            }
            switch (arg) {
                case "-h":
                case "--help":
                    o.help = true;
                    break;
                case "--allow-config-edit":
                    o.allowConfigEdit = Boolean.TRUE;
                    break;
                case "--no-config-edit":
                    o.allowConfigEdit = Boolean.FALSE;
                    break;
                case "--placeholders-file":
                    o.placeholdersFile = Paths.get(nonEmpty(value(args, i, inline, arg), arg));
                    i += inline == null ? 1 : 0;
                    break;
                case "--no-insecure-tls":
                    o.noInsecureTls = true;
                    break;
                case "--host":
                    o.host = nonEmpty(value(args, i, inline, arg), arg);
                    i += inline == null ? 1 : 0;
                    break;
                case "--port":
                    o.port = integer(value(args, i, inline, arg), arg);
                    i += inline == null ? 1 : 0;
                    break;
                case "--ui-user":
                    o.uiUser = nonEmpty(value(args, i, inline, arg), arg);
                    i += inline == null ? 1 : 0;
                    break;
                case "--ui-password":
                    o.uiPassword = nonEmpty(value(args, i, inline, arg), arg);
                    i += inline == null ? 1 : 0;
                    break;
                case "--ui-password-env":
                    String variable = value(args, i, inline, arg);
                    i += inline == null ? 1 : 0;
                    String secret = env.get(variable);
                    if (secret == null || secret.isEmpty()) {
                        throw new ConfigException("The environment variable '" + variable + "' (needed by "
                                + "--ui-password-env) is not set or is empty");
                    }
                    o.uiPassword = secret;
                    break;
                case "--allow-host":
                    o.allowedHosts.add(nonEmpty(value(args, i, inline, arg), arg));
                    i += inline == null ? 1 : 0;
                    break;
                case "--profile-file":
                    o.profileFile = Paths.get(nonEmpty(value(args, i, inline, arg), arg));
                    i += inline == null ? 1 : 0;
                    break;
                case "--attachments-dir":
                    o.attachmentsDir = Paths.get(nonEmpty(value(args, i, inline, arg), arg)).toAbsolutePath().normalize();
                    o.attachmentsDirExplicit = true;
                    i += inline == null ? 1 : 0;
                    break;
                case "--max-body-mb":
                    o.maxBodyBytes = megabytes(value(args, i, inline, arg), arg);
                    i += inline == null ? 1 : 0;
                    break;
                case "--max-response-mb":
                    o.maxResponseBytes = megabytes(value(args, i, inline, arg), arg);
                    i += inline == null ? 1 : 0;
                    break;
                default:
                    throw new ConfigException("Unknown argument for serve: " + args[i]);
            }
        }

        if (o.port < 0 || o.port > 65535) {
            throw new ConfigException("The port must be between 0 and 65535");
        }
        if ((o.uiUser == null) != (o.uiPassword == null)) {
            throw new ConfigException("The UI login needs both a user and a password "
                    + "(--ui-user with --ui-password or --ui-password-env, or SOAPCLI_UI_USER and SOAPCLI_UI_PASSWORD)");
        }
        return o;
    }

    private static String value(String[] args, int index, String inline, String option) {
        if (inline != null) {
            return inline;
        }
        if (index + 1 >= args.length) {
            throw new ConfigException("Missing value for " + option);
        }
        return args[index + 1];
    }

    private static String nonEmpty(String value, String option) {
        if (value == null || value.isBlank()) {
            throw new ConfigException("The value of " + option + " must not be empty");
        }
        return value.trim();
    }

    private static int integer(String value, String name) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid value for " + name + ": '" + value + "' is not a whole number");
        }
    }

    private static long megabytes(String value, String name) {
        int mb = integer(value, name);
        if (mb <= 0) {
            throw new ConfigException("The value of " + name + " must be greater than zero");
        }
        return mb * 1024L * 1024L;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return notBlank(s) ? s : null;
    }

    private static boolean isTrue(String s) {
        return s != null && (s.equalsIgnoreCase("true") || s.equals("1") || s.equalsIgnoreCase("yes"));
    }

    public static String usage() {
        return "Usage: java -jar SoapCLI.jar serve [options]\n"
                + "\n"
                + "Starts the web UI: a browser client for the same engine as the command line.\n"
                + "Every option can also be set with the environment variable shown in brackets.\n"
                + "\n"
                + "  --host <addr>             Address to listen on (default 127.0.0.1)  [SOAPCLI_HOST]\n"
                + "  --port <n>                Port (default 8080)  [SOAPCLI_PORT]\n"
                + "  --profile-file <path>     Profiles file shown in the UI  [SOAPCLI_PROFILE_FILE]\n"
                + "  --placeholders-file <path>  Options offered for the placeholders of a request  [SOAPCLI_PLACEHOLDERS_FILE]\n"
                + "  --allow-config-edit       Let the UI change the profiles and placeholders files (the default only when the\n"
                + "                            server listens on this machine; --no-config-edit forbids it)  [SOAPCLI_ALLOW_CONFIG_EDIT]\n"
                + "  --attachments-dir <dir>   Folder the UI may read attachments from, by path; nothing outside it can be\n"
                + "                            attached (default: the working directory)  [SOAPCLI_ATTACHMENTS_DIR]\n"
                + "\n"
                + "Access control (strongly recommended when listening on a network address):\n"
                + "  --ui-user <name>          Require this user (HTTP Basic)  [SOAPCLI_UI_USER]\n"
                + "  --ui-password <pw>        ...and this password  [SOAPCLI_UI_PASSWORD]\n"
                + "  --ui-password-env <VAR>   ...read the password from an environment variable\n"
                + "  --allow-host <pattern>    Only allow calls to these destination hosts; repeatable.\n"
                + "                            Wildcards: *.corp.example.com, 10.1.*  [SOAPCLI_ALLOWED_HOSTS, comma separated]\n"
                + "  --no-insecure-tls         Do not offer the 'skip certificate checks' option  [SOAPCLI_NO_INSECURE_TLS=true]\n"
                + "\n"
                + "Limits:\n"
                + "  --max-body-mb <n>         Largest request accepted from the browser, and most that the files attached\n"
                + "                            by path may add up to (default 25)  [SOAPCLI_MAX_BODY_MB]\n"
                + "  --max-response-mb <n>     Largest response returned to the browser (default 20)  [SOAPCLI_MAX_RESPONSE_MB]\n"
                + "\n"
                + "  -h, --help                Show this help\n"
                + "\n"
                + "Other command: healthcheck   Exit 0 if a UI is answering on --port (used by the Docker HEALTHCHECK).\n";
    }
}
