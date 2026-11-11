package io.github.marcemv90.soapcli.cli;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/** Entry point: wires the real console into {@link Cli} and exits with its result. */
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
        int code = new Cli(System.in, out, err, System.getenv(), System.getProperty("user.home"),
                System.getProperty("os.name"), terminal).run(args);
        out.flush();
        System.exit(code);
    }
}
