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
    /** A second policy that must allow the host too (a workspace narrows the server's), or {@code null}. */
    private HostPolicy also;

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

    /**
     * A policy that allows a host only if both this one and {@code other} do. Neither is changed. This is how a
     * workspace can narrow, but never widen, what the whole server allows.
     */
    public HostPolicy and(HostPolicy other) {
        if (other == null || other.isOpen()) {
            return this;
        }
        HostPolicy both = new HostPolicy(patterns);
        both.also = also == null ? other : also.and(other);
        return both;
    }

    /** True when no restriction is configured. */
    public boolean isOpen() {
        return compiled.isEmpty() && (also == null || also.isOpen());
    }

    public List<String> patterns() {
        return new ArrayList<>(patterns);
    }

    public boolean allows(String host) {
        return allowedByThisList(host) && (also == null || also.allows(host));
    }

    private boolean allowedByThisList(String host) {
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
        if (!allowedByThisList(host)) {
            throw new HostNotAllowedException("The host '" + host + "' is not allowed by this server's policy. "
                    + "Allowed: " + String.join(", ", patterns));
        }
        if (also != null) {
            also.check(host);
        }
    }
}
