package io.github.marcemv90.soapcli.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509KeyManager;

/** Builds the {@link SSLSocketFactory} for client certificates, custom trust stores and {@code --insecure}. */
final class TlsSupport {

    private TlsSupport() {
    }

    static SSLSocketFactory socketFactory(TlsConfig tls) {
        try {
            KeyManager[] keyManagers = tls.hasKeystore() ? keyManagers(tls) : null;
            TrustManager[] trustManagers = trustManagers(tls);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, new SecureRandom());
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new ConfigException("Could not set up TLS: " + e.getMessage(), e);
        }
    }

    private static KeyManager[] keyManagers(TlsConfig tls) throws GeneralSecurityException {
        KeyStore keyStore = load(tls.keystorePath, TlsConfig.inferType(tls.keystoreType, tls.keystorePath),
                tls.keystorePassword, "keystore");
        String alias = resolveAlias(keyStore, tls.keystoreAlias, tls.keystorePath);
        String keyPassword = tls.keystoreAliasPkPass != null ? tls.keystoreAliasPkPass : tls.keystorePassword;

        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        try {
            factory.init(keyStore, keyPassword.toCharArray());
        } catch (GeneralSecurityException e) {
            throw new ConfigException("Could not unlock the private key '" + alias + "' in '" + tls.keystorePath
                    + "' (wrong key password? use --keystore-alias-pkpass if it differs from the keystore "
                    + "password): " + e.getMessage(), e);
        }
        KeyManager[] managers = factory.getKeyManagers();
        for (int i = 0; i < managers.length; i++) {
            if (managers[i] instanceof X509KeyManager) {
                managers[i] = new FixedAliasKeyManager((X509KeyManager) managers[i], alias);
            }
        }
        return managers;
    }

    private static TrustManager[] trustManagers(TlsConfig tls) throws GeneralSecurityException {
        if (tls.insecure) {
            return new TrustManager[]{new TrustEverything()};
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        if (tls.hasTruststore()) {
            factory.init(load(tls.truststorePath, TlsConfig.inferType(tls.truststoreType, tls.truststorePath),
                    tls.truststorePassword, "truststore"));
        } else {
            factory.init((KeyStore) null); // the JVM default (cacerts, honouring -Djavax.net.ssl.trustStore)
        }
        return factory.getTrustManagers();
    }

    private static KeyStore load(String path, String type, String password, String what) throws GeneralSecurityException {
        KeyStore store = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(Paths.get(path))) {
            store.load(in, password == null ? null : password.toCharArray());
        } catch (IOException e) {
            throw new ConfigException("Could not open the " + what + " '" + path + "' as " + type + ": "
                    + e.getMessage() + " (wrong password or type? use --" + what + "-type)", e);
        } catch (CertificateException e) {
            throw new ConfigException("Could not read certificates from the " + what + " '" + path + "': "
                    + e.getMessage(), e);
        }
        return store;
    }

    /** Finds the keystore's own spelling of the alias; with no alias given, the single private key is used. */
    private static String resolveAlias(KeyStore store, String requested, String path) throws GeneralSecurityException {
        List<String> keyAliases = new ArrayList<>();
        List<String> allAliases = new ArrayList<>();
        for (String a : Collections.list(store.aliases())) {
            allAliases.add(a);
            if (store.isKeyEntry(a)) {
                keyAliases.add(a);
            }
        }
        if (requested == null) {
            if (keyAliases.size() == 1) {
                return keyAliases.get(0);
            }
            throw new ConfigException(keyAliases.isEmpty()
                    ? "The keystore '" + path + "' contains no private key to authenticate with"
                    : "The keystore '" + path + "' has several private keys; choose one with --keystore-alias. "
                    + "Available: " + String.join(", ", keyAliases));
        }
        for (String a : allAliases) {
            if (a.equalsIgnoreCase(requested)) {
                if (!store.isKeyEntry(a)) {
                    throw new ConfigException("The alias '" + a + "' in '" + path + "' is a certificate without a "
                            + "private key, so it cannot be used for client authentication");
                }
                return a;
            }
        }
        throw new ConfigException("Alias '" + requested + "' not found in the keystore '" + path + "'. "
                + (keyAliases.isEmpty() ? "It has no private keys."
                : "Available private keys: " + String.join(", ", keyAliases)));
    }

    /** Always offers the chosen alias instead of letting the JVM guess among certificates. */
    private static final class FixedAliasKeyManager extends X509ExtendedKeyManager {
        private final X509KeyManager delegate;
        private final String alias;

        FixedAliasKeyManager(X509KeyManager delegate, String alias) {
            this.delegate = delegate;
            this.alias = alias;
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{alias};
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return alias;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return alias;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return delegate.getServerAliases(keyType, issuers);
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }

        @Override
        public X509Certificate[] getCertificateChain(String a) {
            return delegate.getCertificateChain(a);
        }

        @Override
        public PrivateKey getPrivateKey(String a) {
            return delegate.getPrivateKey(a);
        }
    }

    /** Accepts every certificate. Extending the "extended" variant also switches off the JDK's own host name check. */
    private static final class TrustEverything extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
