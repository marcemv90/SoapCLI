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
                + "  --max-body-mb <n>         Largest request accepted from the browser (default 25)  [SOAPCLI_MAX_BODY_MB]\n"
                + "  --max-response-mb <n>     Largest response returned to the browser (default 20)  [SOAPCLI_MAX_RESPONSE_MB]\n"
                + "\n"
                + "  -h, --help                Show this help\n"
                + "\n"
                + "Other command: healthcheck   Exit 0 if a UI is answering on --port (used by the Docker HEALTHCHECK).\n";
    }
}
