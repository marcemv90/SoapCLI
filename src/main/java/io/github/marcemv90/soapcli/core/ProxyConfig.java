package io.github.marcemv90.soapcli.core;

import java.util.Locale;

/** HTTP or SOCKS proxy, optionally with credentials. */
public final class ProxyConfig {

    /** {@code http} or {@code socks}. */
    public String type;
    public String host;
    public Integer port;
    public String user;
    public String password;

    public boolean isConfigured() {
        return type != null || host != null || port != null || user != null || password != null;
    }

    public boolean hasCredentials() {
        return user != null && password != null;
    }

    public void validate() {
        if (!isConfigured()) {
            return;
        }
        if (host == null) {
            throw new ConfigException("When using a proxy, the proxy host must be provided (profile or --proxy-host)");
        }
        if (port == null || port <= 0 || port > 65535) {
            throw new ConfigException("When using a proxy, the proxy port must be between 1 and 65535 "
                    + "(profile or --proxy-port)");
        }
        if (type == null) {
            throw new ConfigException("When using a proxy, the proxy type must be provided "
                    + "(profile or --proxy-type). Supported: http, socks");
        }
        String t = type.toLowerCase(Locale.ROOT);
        if (!t.equals("http") && !t.equals("socks")) {
            throw new ConfigException("Unsupported proxy type '" + type + "'. Supported: http, socks");
        }
        if ((user == null) != (password == null)) {
            throw new ConfigException("Proxy credentials need both a user and a password "
                    + "(--proxy-user and --proxy-password / --proxy-password-env)");
        }
    }
}
