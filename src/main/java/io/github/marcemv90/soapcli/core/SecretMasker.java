package io.github.marcemv90.soapcli.core;

import java.util.Locale;
import java.util.regex.Pattern;

/** Decides what must never be echoed in clear (dry-run, debug output, {@code --show-profile}...). */
public final class SecretMasker {

    public static final String MASK = "****";

    private static final Pattern SECRET_NAME = Pattern.compile(
            "authorization|authbasic|authbearer|cookie|token|secret|password|passwd|pkpass|api[-_]?key|credential", Pattern.CASE_INSENSITIVE);

    private SecretMasker() {
    }

    public static boolean isSecretName(String name) {
        return name != null && SECRET_NAME.matcher(name).find();
    }

    /** Masks the value of a header if its name suggests credentials; keeps the auth scheme ({@code Basic ****}). */
    public static String maskHeader(String name, String value) {
        if (!isSecretName(name) || value == null || value.isEmpty()) {
            return value;
        }
        if (name.toLowerCase(Locale.ROOT).endsWith("authorization")) {
            int space = value.indexOf(' ');
            if (space > 0) {
                return value.substring(0, space) + " " + MASK;
            }
        }
        return MASK;
    }
}
