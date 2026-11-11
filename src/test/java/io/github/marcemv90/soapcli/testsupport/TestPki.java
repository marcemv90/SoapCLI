package io.github.marcemv90.soapcli.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Generates a small PKI (server cert, client cert, trust stores) with the JDK's own {@code keytool}. */
public final class TestPki {

    public static final String PASSWORD = "changeit";

    public final Path serverKeystore;       // PKCS12 with the server key
    public final Path serverTruststore;     // JKS trusted by the server: contains the client certificate
    public final Path clientTruststore;     // JKS trusted by the client: contains the server certificate
    public final Path clientKeystore;       // PKCS12 with the client key, alias "client"
    public final Path twoKeyKeystore;       // PKCS12 with two private keys (alias "one", "two")

    public TestPki(Path dir) throws Exception {
        serverKeystore = dir.resolve("server.p12");
        serverTruststore = dir.resolve("server-trust.jks");
        clientTruststore = dir.resolve("client-trust.jks");
        clientKeystore = dir.resolve("client.p12");
        twoKeyKeystore = dir.resolve("two.p12");

        genKey(serverKeystore, "server", "CN=localhost", "san=dns:localhost,ip:127.0.0.1");
        genKey(clientKeystore, "client", "CN=test-client", null);
        genKey(twoKeyKeystore, "one", "CN=one", null);
        genKey(twoKeyKeystore, "two", "CN=two", null);

        Path serverCert = dir.resolve("server.cer");
        Path clientCert = dir.resolve("client.cer");
        keytool("-exportcert", "-alias", "server", "-keystore", serverKeystore.toString(), "-storepass", PASSWORD,
                "-file", serverCert.toString());
        keytool("-exportcert", "-alias", "client", "-keystore", clientKeystore.toString(), "-storepass", PASSWORD,
                "-file", clientCert.toString());
        keytool("-importcert", "-noprompt", "-alias", "server", "-file", serverCert.toString(), "-keystore",
                clientTruststore.toString(), "-storetype", "JKS", "-storepass", PASSWORD);
        keytool("-importcert", "-noprompt", "-alias", "client", "-file", clientCert.toString(), "-keystore",
                serverTruststore.toString(), "-storetype", "JKS", "-storepass", PASSWORD);
    }

    /** The server side TLS context; with {@code trustClients} it can validate the client certificate. */
    public SSLContext serverContext() throws Exception {
        KeyStore keys = load(serverKeystore, "PKCS12");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keys, PASSWORD.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(load(serverTruststore, "JKS"));
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return ctx;
    }

    private static KeyStore load(Path path, String type) throws Exception {
        KeyStore ks = KeyStore.getInstance(type);
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, PASSWORD.toCharArray());
        }
        return ks;
    }

    private static void genKey(Path keystore, String alias, String dn, String san) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList("-genkeypair", "-alias", alias, "-keyalg", "RSA",
                "-keysize", "2048", "-dname", dn, "-validity", "7", "-keystore", keystore.toString(),
                "-storetype", "PKCS12", "-storepass", PASSWORD, "-keypass", PASSWORD));
        if (san != null) {
            args.add("-ext");
            args.add(san);
        }
        keytool(args.toArray(new String[0]));
    }

    private static void keytool(String... args) throws Exception {
        String exe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool").toString();
        List<String> command = new ArrayList<>();
        command.add(exe);
        command.addAll(Arrays.asList(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("keytool " + String.join(" ", args) + " failed:\n" + output);
        }
    }
}
