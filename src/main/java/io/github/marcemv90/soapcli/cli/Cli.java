package io.github.marcemv90.soapcli.cli;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.marcemv90.soapcli.core.ConfigException;
import io.github.marcemv90.soapcli.core.PreparedRequest;
import io.github.marcemv90.soapcli.core.Profile;
import io.github.marcemv90.soapcli.core.Profiles;
import io.github.marcemv90.soapcli.core.RequestBuilder;
import io.github.marcemv90.soapcli.core.SoapCliException;
import io.github.marcemv90.soapcli.core.SoapClient;
import io.github.marcemv90.soapcli.core.SoapResponse;
import io.github.marcemv90.soapcli.core.TransportException;
import io.github.marcemv90.soapcli.core.XmlCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The command line application. Everything it touches (streams, environment, home directory) is injected, so tests
 * can run it in-process and the exit code is returned instead of calling {@code System.exit}.
 */
public final class Cli {

    private final InputStream in;
    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> env;
    private final String userHome;
    private final String osName;
    private final boolean terminal;

    /**
     * @param terminal whether stdin/stdout are an interactive terminal (enables colour by default and the
     *                 "reading from stdin" hint)
     */
    public Cli(InputStream in, PrintStream out, PrintStream err, Map<String, String> env, String userHome,
               String osName, boolean terminal) {
        this.in = in;
        this.out = out;
        this.err = err;
        this.env = env;
        this.userHome = userHome;
        this.osName = osName;
        this.terminal = terminal;
    }

    public int run(String[] args) {
        boolean debug = Arrays.asList(args).contains("--debug");
        try {
            return execute(args);
        } catch (UsageException e) {
            err.println("Error: " + e.getMessage());
            err.println("Run with --help to see the available options.");
            return ExitCodes.USAGE;
        } catch (ConfigException e) {
            fail(e, debug);
            return ExitCodes.USAGE;
        } catch (TransportException e) {
            fail(e, debug);
            return ExitCodes.NETWORK;
        } catch (SoapCliException e) {
            fail(e, debug);
            return ExitCodes.USAGE;
        } catch (IOException e) {
            err.println("Error: " + e.getMessage());
            if (debug) {
                e.printStackTrace(err);
            }
            return ExitCodes.USAGE;
        } catch (RuntimeException | Error e) {
            err.println("Unexpected error: " + e);
            if (debug) {
                e.printStackTrace(err);
            } else {
                err.println("Run again with --debug for the stack trace; this looks like a bug.");
            }
            return ExitCodes.USAGE;
        } finally {
            out.flush();
            err.flush();
        }
    }

    private void fail(SoapCliException e, boolean debug) {
        err.println("Error: " + e.getMessage());
        if (debug && e.getCause() != null) {
            e.getCause().printStackTrace(err);
        }
    }

    private int execute(String[] args) throws IOException {
        if (args.length == 0) {
            err.print(Options.usage());
            return ExitCodes.USAGE;
        }

        List<ArgParser.Occurrence> occurrences = ArgParser.parse(args);
        CliOptions o = new CliOptions();

        // 1. options that choose a command or a profile
        for (ArgParser.Occurrence occurrence : occurrences) {
            if (occurrence.spec.early) {
                occurrence.apply(o, env);
            }
        }
        if (o.help) {
            out.print(Options.usage());
            return ExitCodes.OK;
        }
        if (o.version) {
            out.println("SoapCLI " + Version.get());
            return ExitCodes.OK;
        }
        Path profileFile = o.profileFile != null ? Paths.get(o.profileFile) : Profiles.defaultFile(env, userHome);
        if (o.listProfiles || o.listProfilesDetailed) {
            listProfiles(profileFile, o.listProfilesDetailed);
            return ExitCodes.OK;
        }
        if (o.showProfile != null) {
            JsonNode described = Profiles.load(profileFile).describe(o.showProfile);
            out.println(Profiles.toJson(described));
            return ExitCodes.OK;
        }

        // 2. the profile, then the command line on top of it
        if (o.profileName != null) {
            Profile profile = Profiles.load(profileFile).resolve(o.profileName, env);
            profile.warnings.forEach(w -> err.println("Warning: " + w));
            profile.applyTo(o.call, userHome);
            if (profile.theme != null) {
                o.theme = ColorTheme.parse(profile.theme);
            }
        }
        for (ArgParser.Occurrence occurrence : occurrences) {
            if (!occurrence.spec.early) {
                occurrence.apply(o, env);
            }
        }

        // 3. check everything before blocking on stdin or touching the network
        o.call.validate();

        XmlCharsets.Decoded request = readRequest(o.call.requestFile);
        PreparedRequest prepared = RequestBuilder.build(o.call, request.text, request.charset, Version.userAgent());
        prepared.warnings.forEach(w -> err.println("Warning: " + w));

        boolean color = colorEnabled(o);
        if (o.dryRun) {
            RequestPreview.print(out, "==== SOAP-CLI DRY-RUN ====", "==== END SOAP-CLI DRY-RUN ====", prepared, o.call,
                    o.showSecrets, color, o.theme);
            return ExitCodes.OK;
        }
        if (o.debug) {
            RequestPreview.print(err, "==== SOAP-CLI DEBUG: REQUEST ====", "==== END SOAP-CLI DEBUG: REQUEST ====",
                    prepared, o.call, o.showSecrets, color, o.theme);
        }

        SoapResponse response = new SoapClient().send(prepared, o.call.transport);

        ResponsePrinter printer = new ResponsePrinter(out, err, color && o.outputFile == null, o.theme);
        printer.printStatus(response, o.quiet, o.include || o.debug, o.showSecrets, o.call.transport.followRedirects);
        printer.printBody(response, o.raw, o.outputFile);
        if (o.outputFile != null && !o.quiet) {
            err.println("Response body saved to " + o.outputFile);
        }
        return response.isSuccess() ? ExitCodes.OK : ExitCodes.HTTP;
    }

    private XmlCharsets.Decoded readRequest(String requestFile) throws IOException {
        byte[] bytes;
        if (requestFile == null || requestFile.equals("-")) {
            if (terminal) {
                err.println("Reading the request from standard input (end with Ctrl-D, or Ctrl-Z then Enter on "
                        + "Windows)...");
            }
            bytes = in.readAllBytes();
        } else {
            Path path = Paths.get(requestFile);
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new ConfigException("The request file '" + requestFile + "' does not exist or is not readable");
            }
            bytes = Files.readAllBytes(path);
        }
        return XmlCharsets.decode(bytes, null);
    }

    private boolean colorEnabled(CliOptions o) {
        switch (o.color) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            default:
                String noColor = env.get("NO_COLOR");
                if (noColor != null && !noColor.isEmpty()) {
                    return false;
                }
                String force = env.get("FORCE_COLOR");
                if (force != null && !force.isEmpty() && !force.equals("0")) {
                    return true;
                }
                return terminal && Terminal.ansiSupported(env, osName);
        }
    }

    private void listProfiles(Path file, boolean detailed) {
        Profiles profiles = Profiles.load(file);
        List<String> names = profiles.names();
        if (names.isEmpty()) {
            out.println("No profiles defined in '" + file + "'.");
            return;
        }
        out.println((detailed ? "Profiles details (from '" : "Available profiles (from '") + file + "'):");
        for (String name : names) {
            if (!detailed) {
                out.println("- " + name);
                continue;
            }
            out.println("- " + name + ":");
            try {
                Profiles.render(profiles.describe(name), 4).forEach(out::println);
            } catch (ConfigException e) {
                out.println("    (error: " + e.getMessage() + ")");
            }
        }
    }
}
