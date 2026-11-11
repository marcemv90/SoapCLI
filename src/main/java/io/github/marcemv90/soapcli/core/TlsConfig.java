package io.github.marcemv90.soapcli.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Client certificate (mutual TLS), custom trust, and the "I know what I'm doing" switch. */
public final class TlsConfig {

    public String keystorePath;
    /** {@code JKS} or {@code PKCS12}; inferred from the file extension when {@code null}. */
    public String keystoreType;
    /** Alias of the client key; optional when the keystore holds exactly one private key. */
    public String keystoreAlias;
    public String keystorePassword;
    /** Password of the private key if it differs from the keystore password. */
    public String keystoreAliasPkPass;

    public String truststorePath;
    public String truststoreType;
    public String truststorePassword;

    /** Trust any certificate and skip host name verification. Only for tests against self-signed servers. */
    public boolean insecure;

    public boolean hasKeystore() {
        return keystorePath != null;
    }

    public boolean hasTruststore() {
        return truststorePath != null;
    }

    /** True when the JVM's default TLS setup is not enough. */
    public boolean isCustom() {
        return hasKeystore() || hasTruststore() || insecure;
    }

    public void validate() {
        if (hasKeystore()) {
            requireReadable(keystorePath, "keystore");
            if (keystorePassword == null) {
                throw new ConfigException("When using a keystore, its password must be provided "
                        + "(profile, --keystore-password or --keystore-password-env)");
            }
        }
        if (hasTruststore()) {
            requireReadable(truststorePath, "truststore");
        }
    }

    static String inferType(String explicit, String path) {
        if (explicit != null) {
            return explicit;
        }
        String p = path.toLowerCase(java.util.Locale.ROOT);
        return p.endsWith(".p12") || p.endsWith(".pfx") || p.endsWith(".pkcs12") ? "PKCS12" : "JKS";
    }

    private static void requireReadable(String path, String what) {
        Path p = Paths.get(path);
        if (!Files.isRegularFile(p) || !Files.isReadable(p)) {
            throw new ConfigException("The " + what + " file '" + path + "' does not exist or is not readable");
        }
    }
}
