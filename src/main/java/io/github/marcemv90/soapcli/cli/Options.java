package io.github.marcemv90.soapcli.cli;

import io.github.marcemv90.soapcli.core.SoapVersion;
import io.github.marcemv90.soapcli.core.Version;
import io.github.marcemv90.soapcli.core.XmlFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The table of every option. It drives both the parser and the {@code --help} text, so they cannot disagree. */
final class Options {

    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.\\-]*");

    static final List<OptionSpec> ALL = new ArrayList<>();

    static {
        String request = "Request";
        value(request, "<URL>", "SOAP endpoint (http:// or https://).",
                (o, v, e) -> o.call.endpoint = v, "--endpoint");
        value(request, "<file>", "Request XML. Use - or leave it out to read standard input.",
                (o, v, e) -> o.call.requestFile = v, "--request-file");
        value(request, "<1.1|1.2|auto>", "SOAP version. Default auto: read from the Envelope namespace.\n"
                + "1.1 sends text/xml + a SOAPAction header; 1.2 sends application/soap+xml.",
                (o, v, e) -> o.call.soapVersion = SoapVersion.parse(v), "--soap-version");
        value(request, "<uri>", "SOAP action: the SOAPAction header (1.1) or the action= parameter (1.2).",
                (o, v, e) -> o.call.soapAction = v, "--soap-action");
        value(request, "'Name: value'", "Add an HTTP header (repeatable). Overrides the defaults.",
                (o, v, e) -> {
                    int colon = v.indexOf(':');
                    String name = colon > 0 ? v.substring(0, colon).trim() : "";
                    if (name.isEmpty()) {
                        throw new UsageException("Invalid --header value '" + v + "'. Expected 'Name: value'");
                    }
                    o.call.headers.put(name, v.substring(colon + 1).trim());
                }, "--header");
        value(request, "NAME=VALUE", "Value for a ___NAME:default___ placeholder in the request (repeatable).\n"
                + "The value is inserted as written.",
                (o, v, e) -> placeholder(o, v, false, "--arg"), "--arg", "--placeholder");
        value(request, "NAME=VALUE", "Like --arg, but the value is XML-escaped (& < > \" ').",
                (o, v, e) -> placeholder(o, v, true, "--arg-text"), "--arg-text");
        value(request, "CID=path", "Attach a file referenced as cid:CID in the request (repeatable).\n"
                + "Default: inlined as Base64 text. With --mtom: sent as a MIME part.",
                (o, v, e) -> {
                    int eq = v.indexOf('=');
                    if (eq <= 0 || eq == v.length() - 1) {
                        throw new UsageException("Invalid --attachment value '" + v + "'. Expected CID=path");
                    }
                    o.call.attachments.put(v.substring(0, eq).trim(), v.substring(eq + 1).trim());
                }, "--attachment");
        flag(request, "Send attachments as MTOM/XOP (multipart/related) instead of inline Base64.",
                (o, v, e) -> o.call.mtom = true, "--mtom");

        String auth = "Authentication";
        value(auth, "<user:pass>", "HTTP Basic credentials.",
                (o, v, e) -> {
                    o.call.authBasic = v;
                    o.basicFromFlag = true;
                }, "--auth-basic");
        value(auth, "<ENV_VAR>", "HTTP Basic credentials (user:pass) read from an environment variable.\n"
                + "--auth-basic wins if both are given.",
                (o, v, e) -> {
                    if (!o.basicFromFlag) {
                        o.call.authBasic = fromEnv(e, v, "--auth-basic-env");
                    }
                }, "--auth-basic-env");
        value(auth, "<token>", "Send 'Authorization: Bearer <token>'.",
                (o, v, e) -> o.call.authBearer = v, "--auth-bearer");
        value(auth, "<ENV_VAR>", "Bearer token read from an environment variable.",
                (o, v, e) -> o.call.authBearer = fromEnv(e, v, "--auth-bearer-env"), "--auth-bearer-env");

        String tls = "TLS";
        value(tls, "<file>", "Keystore with the client certificate and key (JKS or PKCS12).",
                (o, v, e) -> o.call.transport.tls.keystorePath = v, "--keystore");
        value(tls, "<type>", "JKS or PKCS12. Default: from the extension (.p12/.pfx = PKCS12, else JKS).",
                (o, v, e) -> o.call.transport.tls.keystoreType = v, "--keystore-type");
        value(tls, "<alias>", "Key to use. Optional when the keystore holds a single private key.",
                (o, v, e) -> o.call.transport.tls.keystoreAlias = v, "--keystore-alias");
        value(tls, "<password>", "Keystore password (also the key password unless --keystore-alias-pkpass).",
                (o, v, e) -> o.call.transport.tls.keystorePassword = v, "--keystore-password");
        value(tls, "<ENV_VAR>", "Keystore password read from an environment variable.",
                (o, v, e) -> o.call.transport.tls.keystorePassword = fromEnv(e, v, "--keystore-password-env"),
                "--keystore-password-env");
        value(tls, "<password>", "Private key password, if different from the keystore password.",
                (o, v, e) -> o.call.transport.tls.keystoreAliasPkPass = v, "--keystore-alias-pkpass");
        value(tls, "<ENV_VAR>", "Private key password read from an environment variable.",
                (o, v, e) -> o.call.transport.tls.keystoreAliasPkPass = fromEnv(e, v, "--keystore-alias-pkpass-env"),
                "--keystore-alias-pkpass-env");
        value(tls, "<file>", "Truststore with the CAs/certificates to trust instead of the JVM default.",
                (o, v, e) -> o.call.transport.tls.truststorePath = v, "--truststore");
        value(tls, "<type>", "JKS or PKCS12 (default: from the extension).",
                (o, v, e) -> o.call.transport.tls.truststoreType = v, "--truststore-type");
        value(tls, "<password>", "Truststore password.",
                (o, v, e) -> o.call.transport.tls.truststorePassword = v, "--truststore-password");
        value(tls, "<ENV_VAR>", "Truststore password read from an environment variable.",
                (o, v, e) -> o.call.transport.tls.truststorePassword = fromEnv(e, v, "--truststore-password-env"),
                "--truststore-password-env");
        flag(tls, "Do NOT verify the server certificate or host name. For throw-away tests only.",
                (o, v, e) -> o.call.transport.tls.insecure = true, "--insecure");

        String proxy = "Proxy";
        value(proxy, "<http|socks>", "Proxy type.", (o, v, e) -> o.call.transport.proxy.type = v, "--proxy-type");
        value(proxy, "<host>", "Proxy host name or IP.", (o, v, e) -> o.call.transport.proxy.host = v, "--proxy-host");
        value(proxy, "<port>", "Proxy port.",
                (o, v, e) -> o.call.transport.proxy.port = integer(v, "--proxy-port"), "--proxy-port");
        value(proxy, "<user>", "Proxy user name.", (o, v, e) -> o.call.transport.proxy.user = v, "--proxy-user");
        value(proxy, "<password>", "Proxy password.",
                (o, v, e) -> o.call.transport.proxy.password = v, "--proxy-password");
        value(proxy, "<ENV_VAR>", "Proxy password read from an environment variable.",
                (o, v, e) -> o.call.transport.proxy.password = fromEnv(e, v, "--proxy-password-env"),
                "--proxy-password-env");

        String net = "Connection";
        value(net, "<seconds>", "How long to wait for the server's answer (default 120, 0 = forever).",
                (o, v, e) -> o.call.transport.readTimeoutSeconds = integer(v, "--timeout"), "--timeout");
        value(net, "<seconds>", "How long to wait to establish the connection (default 30).",
                (o, v, e) -> o.call.transport.connectTimeoutSeconds = integer(v, "--connect-timeout"),
                "--connect-timeout");
        flag(net, "Follow 301/302/307/308 redirects by re-sending the same POST (up to 5).\n"
                + "Credentials are dropped when the redirect leaves the original host.",
                (o, v, e) -> o.call.transport.followRedirects = true, "--follow-redirects");

        String profiles = "Profiles";
        earlyValue(profiles, "<name>", "Use a profile from the profiles file.",
                (o, v, e) -> o.profileName = v, "--profile");
        earlyValue(profiles, "<path>", "Profiles file (default: $SOAPCLI_PROFILE_FILE or ~/.config/soap-cli-profiles.json).",
                (o, v, e) -> o.profileFile = v, "--profile-file");
        earlyValue(profiles, "<name>", "Use a workspace: its profiles.json is the profiles file, its secrets.env feeds ${NAME}\n"
                + "in the profiles, and the relative paths in them start in its folder.",
                (o, v, e) -> o.workspace = v, "--workspace");
        earlyValue(profiles, "<dir>", "The folder that holds the workspaces (default: $SOAPCLI_WORKSPACES_DIR).",
                (o, v, e) -> o.workspacesDir = v, "--workspaces-dir");
        earlyFlag(profiles, "List the profile names and exit.", (o, v, e) -> o.listProfiles = true, "--list-profiles");
        earlyFlag(profiles, "List the profiles with their settings (secrets masked) and exit.",
                (o, v, e) -> o.listProfilesDetailed = true, "--list-profiles-detailed");
        earlyValue(profiles, "<name>", "Print one profile as JSON, after 'extends' (secrets masked), and exit.",
                (o, v, e) -> o.showProfile = v, "--show-profile");

        String output = "Output";
        value(output, "<name>", "Colour theme: monokai, solarized-dark, solarized-light, darcula (default),\n"
                + "gruvbox-dark, gruvbox-light, dracula, nord.",
                (o, v, e) -> o.theme = ColorTheme.parse(v), "--theme");
        flag(output, "Always colour the XML, even when the output is not a terminal.",
                (o, v, e) -> o.color = CliOptions.ColorMode.ALWAYS, "--color");
        flag(output, "Never colour the XML. (Default: colour only on a terminal; NO_COLOR is honoured.)",
                (o, v, e) -> o.color = CliOptions.ColorMode.NEVER, "--no-color");
        flag(output, "Print the response body exactly as received: no formatting, no colour.",
                (o, v, e) -> o.raw = true, "--raw");
        value(output, "<file>", "Write the response body to a file instead of standard output.",
                (o, v, e) -> o.outputFile = v, "-o", "--output");
        flag(output, "Also print the response headers (on stderr).",
                (o, v, e) -> o.include = true, "-i", "--include");
        flag(output, "Do not print the HTTP status line.", (o, v, e) -> o.quiet = true, "-q", "--quiet");
        flag(output, "Show the final request (headers, body) on standard output and exit without sending.",
                (o, v, e) -> o.dryRun = true, "--dry-run");
        flag(output, "Show the request and the response headers on stderr, plus stack traces.",
                (o, v, e) -> o.debug = true, "--debug");
        flag(output, "Do not mask Authorization, Cookie... in --dry-run/--debug/-i output.",
                (o, v, e) -> o.showSecrets = true, "--show-secrets");

        earlyFlag("General", "Show this help.", (o, v, e) -> o.help = true, "-h", "--help");
        earlyFlag("General", "Show the version.", (o, v, e) -> o.version = true, "--version");
    }

    private Options() {
    }

    private static void flag(String group, String help, OptionSpec.Handler h, String... names) {
        ALL.add(new OptionSpec(group, names, null, help, false, h));
    }

    private static void value(String group, String valueName, String help, OptionSpec.Handler h, String... names) {
        ALL.add(new OptionSpec(group, names, valueName, help, false, h));
    }

    private static void earlyFlag(String group, String help, OptionSpec.Handler h, String... names) {
        ALL.add(new OptionSpec(group, names, null, help, true, h));
    }

    private static void earlyValue(String group, String valueName, String help, OptionSpec.Handler h, String... names) {
        ALL.add(new OptionSpec(group, names, valueName, help, true, h));
    }

    private static void placeholder(CliOptions o, String spec, boolean escape, String option) {
        int eq = spec.indexOf('=');
        String name = eq > 0 ? spec.substring(0, eq).trim() : "";
        if (!PLACEHOLDER_NAME.matcher(name).matches()) {
            throw new UsageException("Invalid " + option + " value '" + spec + "'. Expected NAME=VALUE, where NAME "
                    + "uses letters, digits, _ . -");
        }
        String value = spec.substring(eq + 1);
        o.call.args.put(name, escape ? XmlFormat.escape(value) : value);
    }

    private static int integer(String value, String option) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new UsageException("Invalid value for " + option + ": '" + value + "' is not a whole number");
        }
    }

    private static String fromEnv(Map<String, String> env, String variable, String option) {
        String value = env.get(variable);
        if (value == null || value.isEmpty()) {
            throw new UsageException("The environment variable '" + variable + "' (needed by " + option
                    + ") is not set or is empty");
        }
        return value;
    }

    // ---- help text ------------------------------------------------------------------------------------------------

    static String usage() {
        StringBuilder sb = new StringBuilder();
        sb.append("SoapCLI ").append(Version.get()).append(" - send a SOAP request from the command line\n\n");
        sb.append("Usage: java -jar SoapCLI.jar --endpoint <URL> [--request-file <file.xml>] [options]\n");
        sb.append("       cat request.xml | java -jar SoapCLI.jar --endpoint <URL> [options]\n");
        sb.append("       java -jar SoapCLI.jar serve [options]      start the web UI (try: serve --help)\n");

        Map<String, List<OptionSpec>> groups = new LinkedHashMap<>();
        for (OptionSpec spec : ALL) {
            groups.computeIfAbsent(spec.group, g -> new ArrayList<>()).add(spec);
        }
        for (Map.Entry<String, List<OptionSpec>> group : groups.entrySet()) {
            sb.append('\n').append(group.getKey()).append(":\n");
            for (OptionSpec spec : group.getValue()) {
                String left = "  " + String.join(", ", Arrays.asList(spec.names))
                        + (spec.takesValue() ? " " + spec.valueName : "");
                String[] lines = spec.help.split("\n");
                if (left.length() > 34) {
                    sb.append(left).append('\n');
                    left = "";
                }
                for (int i = 0; i < lines.length; i++) {
                    sb.append(String.format("%-36s", i == 0 ? left : "")).append(lines[i]).append('\n');
                }
            }
        }
        sb.append("\nExit codes:\n");
        sb.append("  0   the server answered with HTTP 2xx\n");
        sb.append("  1   bad command line, profile, file or keystore (nothing was sent)\n");
        sb.append("  2   the request could not be delivered (DNS, connection, timeout, TLS, proxy)\n");
        sb.append("  3   the server answered with another status (4xx, 5xx / SOAP fault, unfollowed redirect)\n");
        sb.append("\nThe HTTP status is printed on stderr; the response body is the only thing on stdout.\n");
        sb.append("Documentation: https://marcemv90.github.io/SoapCLI/\n");
        return sb.toString();
    }
}
