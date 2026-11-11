package io.github.marcemv90.soapcli.cli;

import java.io.Console;
import java.util.Locale;
import java.util.Map;

/** What we can know about the terminal the user is looking at. */
final class Terminal {

    private Terminal() {
    }

    /**
     * Whether the JVM is attached to an interactive terminal. On Java up to 21 {@code System.console()} is non-null
     * only if stdin <em>and</em> stdout are terminals; from Java 22 it is always non-null and {@code isTerminal()}
     * answers the question.
     */
    static boolean interactive() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            return (Boolean) Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            return true; // Java < 22: a non-null console means a terminal
        }
    }

    /**
     * Legacy Windows consoles print escape sequences as garbage. Windows Terminal, ConEmu, ANSICON and the Unix-like
     * shells (Git Bash, MSYS, Cygwin: they define TERM) are fine. Everything that is not Windows is assumed fine.
     */
    static boolean ansiSupported(Map<String, String> env, String osName) {
        if (osName == null || !osName.toLowerCase(Locale.ROOT).contains("win")) {
            return true;
        }
        return env.containsKey("WT_SESSION") || env.containsKey("ANSICON") || "ON".equals(env.get("ConEmuANSI"))
                || env.containsKey("TERM") || env.containsKey("TERM_PROGRAM");
    }
}
