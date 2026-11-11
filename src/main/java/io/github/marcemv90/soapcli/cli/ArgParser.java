package io.github.marcemv90.soapcli.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Splits the command line into (option, value) pairs using the {@link Options} table. */
final class ArgParser {

    static final class Occurrence {
        final OptionSpec spec;
        final String value;

        Occurrence(OptionSpec spec, String value) {
            this.spec = spec;
            this.value = value;
        }

        void apply(CliOptions options, Map<String, String> env) {
            spec.handler.apply(options, value, env);
        }
    }

    private static final Map<String, OptionSpec> BY_NAME = new HashMap<>();

    static {
        for (OptionSpec spec : Options.ALL) {
            for (String name : spec.names) {
                BY_NAME.put(name, spec);
            }
        }
    }

    private ArgParser() {
    }

    /**
     * Accepts {@code --name value} and {@code --name=value}. A value may start with a dash
     * ({@code --header --weird}) because the option, not the value, decides what is a value.
     */
    static List<Occurrence> parse(String[] args) {
        List<Occurrence> result = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String name = arg;
            String inline = null;
            int eq = arg.startsWith("--") ? arg.indexOf('=') : -1;
            if (eq > 2) {
                OptionSpec candidate = BY_NAME.get(arg.substring(0, eq));
                if (candidate != null && candidate.takesValue()) {
                    name = arg.substring(0, eq);
                    inline = arg.substring(eq + 1);
                }
            }
            OptionSpec spec = BY_NAME.get(name);
            if (spec == null) {
                throw new UsageException("Unknown argument: " + arg);
            }
            String value = null;
            if (spec.takesValue()) {
                if (inline != null) {
                    value = inline;
                } else if (i + 1 < args.length) {
                    value = args[++i];
                } else {
                    throw new UsageException("Missing value for " + name + " " + spec.valueName);
                }
            }
            result.add(new Occurrence(spec, value));
        }
        return result;
    }
}
