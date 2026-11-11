package io.github.marcemv90.soapcli.core;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy plumbing.
 *
 * <p>{@code HttpURLConnection} can only authenticate to a proxy through the JVM-wide {@link Authenticator}. Instead of
 * installing a new one for every request (which would break as soon as two calls with different proxies overlap, as
 * they will in a web UI) we install a single authenticator once and keep the credentials in a thread-safe registry.
 */
final class ProxySupport {

    private static final Map<String, PasswordAuthentication> CREDENTIALS = new ConcurrentHashMap<>();
    private static final Authenticator AUTHENTICATOR = new Authenticator() {
        @Override
        protected PasswordAuthentication getPasswordAuthentication() {
            if (getRequestorType() != RequestorType.PROXY) {
                return null; // never hand proxy credentials to an origin server
            }
            PasswordAuthentication found = CREDENTIALS.get(key(getRequestingHost(), getRequestingPort()));
            if (found == null && CREDENTIALS.size() == 1) {
                found = CREDENTIALS.values().iterator().next();
            }
            return found == null ? null : new PasswordAuthentication(found.getUserName(), found.getPassword());
        }
    };

    private ProxySupport() {
    }

    /** @return the {@link Proxy} to use, or {@code null} for a direct connection */
    static synchronized Proxy toJavaProxy(ProxyConfig config) {
        if (config.host == null || config.port == null) {
            return null;
        }
        Proxy.Type type = "socks".equals(config.type.toLowerCase(Locale.ROOT)) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        if (config.hasCredentials()) {
            CREDENTIALS.put(key(config.host, config.port),
                    new PasswordAuthentication(config.user, config.password.toCharArray()));
            if (Authenticator.getDefault() != AUTHENTICATOR) {
                Authenticator.setDefault(AUTHENTICATOR);
            }
        }
        return new Proxy(type, new InetSocketAddress(config.host, config.port));
    }

    private static String key(String host, int port) {
        return (host == null ? "" : host.toLowerCase(Locale.ROOT)) + ":" + port;
    }
}
