package io.github.marcemv90.soapcli.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the usual {@code NAME=value} file of environment variables (a ".env" file). A workspace keeps the secrets its
 * profiles refer to as {@code ${NAME}} in one ({@code secrets.env}), so that they live next to the profiles instead of in
 * the environment of the whole server.
 *
 * <pre>
 * # a comment
 * ACC_CREDENTIALS=svc:s3cret
 * export KEYSTORE_PASSWORD="change me"      # quotes keep spaces and # as they are
 * </pre>
 *
 * A line that is not of that shape is ignored. An unquoted value ends at a space followed by {@code #}.
 */
public final class DotEnv {

    private static final Pattern LINE = Pattern.compile("^(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.*)$");

    private DotEnv() {
    }

    public static Map<String, String> parse(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        if (text == null) {
            return values;
        }
        String source = text.startsWith("﻿") ? text.substring(1) : text;
        for (String raw : source.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (m.matches()) {
                values.put(m.group(1), value(m.group(2)));
            }
        }
        return values;
    }

    private static String value(String rest) {
        String v = rest.trim();
        if (v.length() >= 2 && (v.charAt(0) == '"' || v.charAt(0) == '\'')) {
            int end = v.indexOf(v.charAt(0), 1);
            if (end > 0) {
                return v.substring(1, end);
            }
        }
        int comment = v.indexOf(" #");
        return (comment >= 0 ? v.substring(0, comment) : v).trim();
    }
}
