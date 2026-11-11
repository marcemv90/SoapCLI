package io.github.marcemv90.soapcli.cli;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import io.github.marcemv90.soapcli.web.ServeCommand;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * Entry point: wires the real console into {@link Cli} (or the web UI) and exits with its result.
 *
 * <ul>
 *   <li>{@code serve [options]}: start the web UI;</li>
 *   <li>{@code healthcheck}: exit 0 if a web UI answers on this machine (used by the Docker HEALTHCHECK);</li>
 *   <li>{@code cli ...}: the command line client, explicitly (handy in a container whose default command is
 *       {@code serve});</li>
 *   <li>anything else: the command line client, as always.</li>
 * </ul>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // A terminal has its own encoding (cp850 on a Windows console, for instance) and the JVM reports it. Anything
        // else (a file, a pipe) gets UTF-8: the platform default would be ASCII under LANG=C or cp1252 on older
        // Windows JDKs, which turns every accent into '?'.
        boolean terminal = Terminal.interactive();
        String encoding = terminal
                ? System.getProperty("stdout.encoding", System.getProperty("sun.stdout.encoding", "UTF-8"))
                : "UTF-8";
        PrintStream out;
        PrintStream err;
        try {
            out = new PrintStream(new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 8192), false, encoding);
            err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, encoding);
        } catch (UnsupportedEncodingException e) {
            out = System.out;
            err = System.err;
        }
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;
        if (args.length > 0 && args[0].equals("serve")) {
            int code = ServeCommand.run(rest, System.getenv(), System.getProperty("user.home"), out, err);
            out.flush();
            System.exit(code);
        }
        if (args.length > 0 && args[0].equals("healthcheck")) {
            int code = ServeCommand.healthcheck(rest, System.getenv(), out, err);
            out.flush();
            System.exit(code);
        }
        if (args.length > 0 && args[0].equals("cli")) {
            args = rest;
        }
        int code = new Cli(System.in, out, err, System.getenv(), System.getProperty("user.home"),
                System.getProperty("os.name"), terminal).run(args);
        out.flush();
        System.exit(code);
    }
}
