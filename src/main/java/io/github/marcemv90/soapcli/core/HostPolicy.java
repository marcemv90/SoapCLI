package io.github.marcemv90.soapcli.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * An allow-list of destination hosts. A server that sends HTTP requests on behalf of its users can be turned into a
 * way to reach internal systems (SSRF); restricting the hosts it may call is the main defence.
 *
 * <p>Patterns are host names or IP addresses, compared case-insensitively and ignoring the port, with {@code *}
 * standing for any run of characters: {@code api.example.com}, {@code *.corp.example.com}, {@code 10.1.*}.
 * Note that {@code *.example.com} does not match {@code example.com} itself; list both if you need both.
 */
public final class HostPolicy {

    private final List<String> patterns;
    private final List<Pattern> compiled = new ArrayList<>();

    public HostPolicy(Collection<String> patterns) {
        this.patterns = new ArrayList<>();
        for (String p : patterns) {
            String trimmed = p == null ? "" : p.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                continue;
            }
            this.patterns.add(trimmed);
            compiled.add(Pattern.compile(toRegex(trimmed)));
        }
    }

    /** Quotes everything literally and turns each {@code *} into {@code .*}. */
    private static String toRegex(String pattern) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(".*");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return regex.toString();
    }

    /** True when no restriction is configured. */
    public boolean isOpen() {
        return compiled.isEmpty();
    }

    public List<String> patterns() {
        return new ArrayList<>(patterns);
    }

    public boolean allows(String host) {
        if (compiled.isEmpty()) {
            return true;
        }
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (Pattern p : compiled) {
            if (p.matcher(h).matches()) {
                return true;
            }
        }
        return false;
    }

    /** @throws HostNotAllowedException if {@code host} is not on the list */
    public void check(String host) {
        if (!allows(host)) {
            throw new HostNotAllowedException("The host '" + host + "' is not allowed by this server's policy. "
                    + "Allowed: " + String.join(", ", patterns));
        }
    }
}
