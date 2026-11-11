package io.github.marcemv90.soapcli.core;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces {@code ___NAME:default___} markers in the request text.
 *
 * <p>The name is restricted to identifier characters. The old pattern accepted <em>anything</em> up to the next colon,
 * so an unrelated {@code ___} elsewhere in the document (or a URL's {@code http:}) made it swallow arbitrary XML.
 */
public final class Placeholders {

    static final Pattern PATTERN = Pattern.compile("___([A-Za-z_][A-Za-z0-9_.\\-]*):(.*?)___", Pattern.DOTALL);

    /** The resulting text and which of the supplied values were actually used. */
    public static final class Result {
        public final String text;
        public final Set<String> usedNames;

        Result(String text, Set<String> usedNames) {
            this.text = text;
            this.usedNames = usedNames;
        }
    }

    private Placeholders() {
    }

    public static Result apply(String body, Map<String, String> values) {
        Set<String> used = new LinkedHashSet<>();
        if (body == null || body.isEmpty()) {
            return new Result(body, used);
        }
        Matcher m = PATTERN.matcher(body);
        StringBuilder sb = new StringBuilder(body.length());
        int last = 0;
        while (m.find()) {
            String name = m.group(1);
            String value;
            if (values.containsKey(name)) {
                value = values.get(name);
                used.add(name);
            } else {
                value = m.group(2);
            }
            sb.append(body, last, m.start()).append(value == null ? "" : value);
            last = m.end();
        }
        sb.append(body, last, body.length());
        return new Result(sb.toString(), used);
    }
}
