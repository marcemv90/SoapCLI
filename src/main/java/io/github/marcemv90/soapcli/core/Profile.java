package io.github.marcemv90.soapcli.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One resolved profile: {@code extends} merged, {@code ${ENV}} references expanded and every value type-checked.
 * Apply it to a {@link CallConfig} before the command line flags, which then override it.
 */
public final class Profile {

    private static final Set<String> TOP = new HashSet<>(Arrays.asList("extends", "endpoint", "requestFile", "theme",
            "soapVersion", "soapAction", "mtom", "timeout", "connectTimeout", "followRedirects", "headers", "args",
            "attachments", "authBasic", "authBearer", "keystore", "truststore", "insecure", "proxy"));
    private static final Set<String> KEYSTORE = new HashSet<>(Arrays.asList("path", "type", "alias", "password", "aliasPkPass"));
    private static final Set<String> TRUSTSTORE = new HashSet<>(Arrays.asList("path", "type", "password"));
    private static final Set<String> PROXY = new HashSet<>(Arrays.asList("type", "host", "port", "user", "password"));

    public final String name;
    public final List<String> warnings = new ArrayList<>();

    public String endpoint;
    public String requestFile;
    public String theme;
    public boolean soapVersionSet;
    public SoapVersion soapVersion;
    public String soapAction;
    public Boolean mtom;
    public Integer timeout;
    public Integer connectTimeout;
    public Boolean followRedirects;
    public final Map<String, String> headers = new LinkedHashMap<>();
    public final Map<String, String> args = new LinkedHashMap<>();
    public final Map<String, String> attachments = new LinkedHashMap<>();
    public String authBasic;
    public String authBearer;
    public String keystorePath;
    public String keystoreType;
    public String keystoreAlias;
    public String keystorePassword;
    public String keystoreAliasPkPass;
    public String truststorePath;
    public String truststoreType;
    public String truststorePassword;
    public Boolean insecure;
    public String proxyType;
    public String proxyHost;
    public Integer proxyPort;
    public String proxyUser;
    public String proxyPassword;

    private Profile(String name) {
        this.name = name;
    }

    /** @param node the merged and interpolated profile */
    static Profile from(String name, JsonNode node) {
        Profile p = new Profile(name);
        p.checkKeys(node, TOP, "");
        p.endpoint = p.text(node, "endpoint");
        p.requestFile = p.text(node, "requestFile");
        p.theme = p.text(node, "theme");
        String version = p.text(node, "soapVersion");
        if (version != null) {
            p.soapVersionSet = true;
            p.soapVersion = SoapVersion.parse(version);
        }
        p.soapAction = p.text(node, "soapAction");
        p.mtom = p.bool(node, "mtom");
        p.timeout = p.integer(node, "timeout");
        p.connectTimeout = p.integer(node, "connectTimeout");
        p.followRedirects = p.bool(node, "followRedirects");
        p.map(node.get("headers"), "headers", p.headers, false);
        p.map(node.get("args"), "args", p.args, true);
        p.map(node.get("attachments"), "attachments", p.attachments, false);
        p.authBasic = p.text(node, "authBasic");
        p.authBearer = p.text(node, "authBearer");
        p.insecure = p.bool(node, "insecure");

        JsonNode ks = p.object(node, "keystore");
        if (ks != null) {
            p.checkKeys(ks, KEYSTORE, "keystore.");
            p.keystorePath = p.text(ks, "keystore.path", "path");
            p.keystoreType = p.text(ks, "keystore.type", "type");
            p.keystoreAlias = p.text(ks, "keystore.alias", "alias");
            p.keystorePassword = p.text(ks, "keystore.password", "password");
            p.keystoreAliasPkPass = p.text(ks, "keystore.aliasPkPass", "aliasPkPass");
        }
        JsonNode ts = p.object(node, "truststore");
        if (ts != null) {
            p.checkKeys(ts, TRUSTSTORE, "truststore.");
            p.truststorePath = p.text(ts, "truststore.path", "path");
            p.truststoreType = p.text(ts, "truststore.type", "type");
            p.truststorePassword = p.text(ts, "truststore.password", "password");
        }
        JsonNode px = p.object(node, "proxy");
        if (px != null) {
            p.checkKeys(px, PROXY, "proxy.");
            p.proxyType = p.text(px, "proxy.type", "type");
            p.proxyHost = p.text(px, "proxy.host", "host");
            p.proxyPort = p.integer(px, "proxy.port", "port");
            p.proxyUser = p.text(px, "proxy.user", "user");
            p.proxyPassword = p.text(px, "proxy.password", "password");
        }
        return p;
    }

    /** Copies every value the profile defines into {@code cfg}; leaves the rest untouched. */
    public void applyTo(CallConfig cfg, String userHome) {
        applyTo(cfg, userHome, null);
    }

    /**
     * Like {@link #applyTo(CallConfig, String)}, but a relative path of the profile ({@code requestFile},
     * {@code attachments}, {@code keystore}, {@code truststore}) is taken from {@code baseDir} instead of the working
     * directory. A workspace uses this so that its folder holds everything its profiles refer to.
     *
     * @param baseDir the folder relative paths start at, or {@code null} for the working directory
     */
    public void applyTo(CallConfig cfg, String userHome, java.nio.file.Path baseDir) {
        if (endpoint != null) {
            cfg.endpoint = endpoint;
        }
        if (requestFile != null) {
            cfg.requestFile = expand(requestFile, userHome, baseDir);
        }
        if (soapVersionSet) {
            cfg.soapVersion = soapVersion;
        }
        if (soapAction != null) {
            cfg.soapAction = soapAction;
        }
        if (mtom != null) {
            cfg.mtom = mtom;
        }
        if (authBasic != null) {
            cfg.authBasic = authBasic;
        }
        if (authBearer != null) {
            cfg.authBearer = authBearer;
        }
        for (Map.Entry<String, String> h : headers.entrySet()) {
            cfg.headers.put(h.getKey(), h.getValue());
        }
        cfg.args.putAll(args);
        for (Map.Entry<String, String> a : attachments.entrySet()) {
            cfg.attachments.put(a.getKey(), expand(a.getValue(), userHome, baseDir));
        }

        TransportOptions t = cfg.transport;
        if (timeout != null) {
            t.readTimeoutSeconds = timeout;
        }
        if (connectTimeout != null) {
            t.connectTimeoutSeconds = connectTimeout;
        }
        if (followRedirects != null) {
            t.followRedirects = followRedirects;
        }
        if (insecure != null) {
            t.tls.insecure = insecure;
        }
        if (keystorePath != null) {
            t.tls.keystorePath = expand(keystorePath, userHome, baseDir);
        }
        if (keystoreType != null) {
            t.tls.keystoreType = keystoreType;
        }
        if (keystoreAlias != null) {
            t.tls.keystoreAlias = keystoreAlias;
        }
        if (keystorePassword != null) {
            t.tls.keystorePassword = keystorePassword;
        }
        if (keystoreAliasPkPass != null) {
            t.tls.keystoreAliasPkPass = keystoreAliasPkPass;
        }
        if (truststorePath != null) {
            t.tls.truststorePath = expand(truststorePath, userHome, baseDir);
        }
        if (truststoreType != null) {
            t.tls.truststoreType = truststoreType;
        }
        if (truststorePassword != null) {
            t.tls.truststorePassword = truststorePassword;
        }
        if (proxyType != null) {
            t.proxy.type = proxyType;
        }
        if (proxyHost != null) {
            t.proxy.host = proxyHost;
        }
        if (proxyPort != null) {
            t.proxy.port = proxyPort;
        }
        if (proxyUser != null) {
            t.proxy.user = proxyUser;
        }
        if (proxyPassword != null) {
            t.proxy.password = proxyPassword;
        }
    }

    // ---- typed access --------------------------------------------------------------------------------------------

    private String where(String path) {
        return "Profile '" + name + "': '" + path + "'";
    }

    private String text(JsonNode parent, String key) {
        return text(parent, key, key);
    }

    /** A string setting; {@code null}, empty and blank values mean "not set", as the docs promise. */
    private String text(JsonNode parent, String path, String key) {
        JsonNode n = parent.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isContainerNode()) {
            throw new ConfigException(where(path) + " must be a string, number or boolean");
        }
        String value = n.asText();
        return value.isBlank() ? null : value;
    }

    private Integer integer(JsonNode parent, String key) {
        return integer(parent, key, key);
    }

    private Integer integer(JsonNode parent, String path, String key) {
        String value = text(parent, path, key);
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new ConfigException(where(path) + " must be a whole number, got '" + value + "'");
        }
    }

    private Boolean bool(JsonNode parent, String key) {
        String value = text(parent, key);
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (v.equals("true")) {
            return Boolean.TRUE;
        }
        if (v.equals("false")) {
            return Boolean.FALSE;
        }
        throw new ConfigException(where(key) + " must be true or false, got '" + value + "'");
    }

    private JsonNode object(JsonNode parent, String key) {
        JsonNode n = parent.get(key);
        if (n == null || n.isNull()) {
            return null;
        }
        if (!n.isObject()) {
            throw new ConfigException(where(key) + " must be an object");
        }
        return n;
    }

    private void map(JsonNode n, String key, Map<String, String> target, boolean keepEmpty) {
        if (n == null || n.isNull()) {
            return;
        }
        if (!n.isObject()) {
            throw new ConfigException(where(key) + " must be an object of name/value pairs");
        }
        Iterator<Map.Entry<String, JsonNode>> it = n.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode v = e.getValue();
            if (v.isNull()) {
                continue;
            }
            if (v.isContainerNode()) {
                throw new ConfigException(where(key + "." + e.getKey()) + " must be a string, number or boolean");
            }
            String value = v.asText();
            if (keepEmpty || !value.isEmpty()) {
                target.put(e.getKey(), value);
            }
        }
    }

    private void checkKeys(JsonNode node, Set<String> known, String prefix) {
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            if (!known.contains(key)) {
                warnings.add("Profile '" + name + "': unknown setting '" + prefix + key + "' is ignored");
            }
        }
    }

    private static String expand(String path, String userHome, java.nio.file.Path baseDir) {
        if (userHome != null && (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\"))) {
            return userHome + path.substring(1);
        }
        if (baseDir != null) {
            try {
                java.nio.file.Path given = java.nio.file.Paths.get(path);
                if (!given.isAbsolute() && !path.startsWith("/") && !path.startsWith("\\")) {
                    return baseDir.resolve(given).normalize().toString();
                }
            } catch (java.nio.file.InvalidPathException e) {
                // left as written: the code that opens the file reports it
            }
        }
        return path;
    }
}
