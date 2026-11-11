package io.github.marcemv90.soapcli.cli;

import java.util.Map;

/** One command-line option: its names, whether it takes a value, its help text and what it does to the options. */
final class OptionSpec {

    @FunctionalInterface
    interface Handler {
        void apply(CliOptions options, String value, Map<String, String> env);
    }

    final String group;
    final String[] names;
    /** {@code null} for flags. */
    final String valueName;
    final String help;
    final Handler handler;
    /** Early options select commands and profiles; they are applied before the profile is loaded. */
    final boolean early;

    OptionSpec(String group, String[] names, String valueName, String help, boolean early, Handler handler) {
        this.group = group;
        this.names = names;
        this.valueName = valueName;
        this.help = help;
        this.early = early;
        this.handler = handler;
    }

    boolean takesValue() {
        return valueName != null;
    }
}
