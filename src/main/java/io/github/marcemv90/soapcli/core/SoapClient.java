package io.github.marcemv90.soapcli.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;

/**
 * Sends a {@link PreparedRequest} over HTTP(S) and returns the raw {@link SoapResponse}.
 *
 * <p>It prints nothing and never exits the JVM, and holds no per-call state, so it can be shared between threads.
 */
public final class SoapClient {

    private static final int MAX_REDIRECTS = 5;
    /** Above this size the body is streamed instead of being buffered a second time inside HttpURLConnection. */
    private static final long STREAMING_THRESHOLD = 8L * 1024 * 1024;

    static {
        // Lets users send headers HttpURLConnection otherwise drops silently (Host, Connection...), e.g. to reach a
        // virtual host through its IP address. Must be set before HttpURLConnection is first used.
        setIfAbsent("sun.net.http.allowRestrictedHeaders", "true");
        // Since Java 8u111 Basic authentication to a proxy is refused for HTTPS tunnels. The user gave us the proxy
        // credentials explicitly, so honour them.
        setIfAbsent("jdk.http.auth.tunneling.disabledSchemes", "");
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    public SoapResponse send(PreparedRequest request, TransportOptions options) {
        long started = System.nanoTime();
        URI uri = request.endpoint;
        HeaderMap headers = request.headers.copy();
        int redirects = 0;
        while (true) {
            if (options.hostPolicy != null) {
                options.hostPolicy.check(uri.getHost()); // before any DNS lookup or connection, on every hop
            }
            SoapResponse response;
            try {
                response = exchange(uri, headers, request.body, options, started);
            } catch (IOException e) {
                throw describe(e, uri, options);
            }
            if (!options.followRedirects || !isFollowable(response.status) || redirects >= MAX_REDIRECTS) {
                return response;
            }
            String location = response.header("Location");
            if (location == null) {
                return response;
            }
            URI next = uri.resolve(location);
            String scheme = next.getScheme() == null ? "" : next.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return response;
            }
            if (!sameOrigin(uri, next)) {
                headers.remove("Authorization"); // never leak credentials to another host
                headers.remove("Cookie");
            }
            uri = next;
            redirects++;
        }
    }

    private SoapResponse exchange(URI uri, HeaderMap headers, RequestBody body, TransportOptions options,
                                  long started) throws IOException {
        Proxy proxy = options.proxy.isConfigured() ? ProxySupport.toJavaProxy(options.proxy) : null;
        HttpURLConnection conn = (HttpURLConnection) (proxy != null
                ? uri.toURL().openConnection(proxy)
                : uri.toURL().openConnection());
        try {
            if (conn instanceof HttpsURLConnection && options.tls.isCustom()) {
                HttpsURLConnection https = (HttpsURLConnection) conn;
                https.setSSLSocketFactory(TlsSupport.socketFactory(options.tls));
                if (options.tls.insecure) {
                    https.setHostnameVerifier((host, session) -> true);
                }
            }

            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(options.connectTimeoutSeconds * 1000);
            conn.setReadTimeout(options.readTimeoutSeconds * 1000);

            // Headers must be set BEFORE the output stream is opened: after that HttpURLConnection is "connected" and
            // setRequestProperty throws IllegalStateException.
            for (Map.Entry<String, String> h : headers.entries()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }

            long length = body.contentLength();
            // Streaming cannot be replayed, so it would break proxy authentication on plain HTTP: only use it for
            // big bodies without proxy credentials.
            if (length > STREAMING_THRESHOLD && !options.proxy.hasCredentials()) {
                conn.setFixedLengthStreamingMode(length);
            }
            try (OutputStream out = conn.getOutputStream()) {
                body.writeTo(out);
            }

            int status = conn.getResponseCode();
            if (status == -1) {
                throw new IOException("The server did not answer with a valid HTTP response");
            }
            String message = conn.getResponseMessage();
            Map<String, List<String>> responseHeaders = conn.getHeaderFields();

            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            byte[] bytes = new byte[0];
            if (in != null) {
                try (InputStream stream = decompress(in, conn.getContentEncoding())) {
                    bytes = readBody(stream, options.maxResponseBytes);
                }
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            return new SoapResponse(status, message, uri, responseHeaders, bytes, elapsed);
        } finally {
            conn.disconnect();
        }
    }

    /** Thrown while reading a response that exceeds {@link TransportOptions#maxResponseBytes}. */
    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        ResponseTooLargeException(long limit) {
            super("The response is larger than the limit of " + limit + " bytes");
        }
    }

    private static byte[] readBody(InputStream stream, long limit) throws IOException {
        if (limit <= 0) {
            return stream.readAllBytes();
        }
        // Read one byte more than allowed so "exactly at the limit" is accepted and "over" is detected.
        byte[] bytes = stream.readNBytes((int) Math.min(limit + 1, Integer.MAX_VALUE - 8));
        if (bytes.length > limit) {
            throw new ResponseTooLargeException(limit);
        }
        return bytes;
    }

    private static InputStream decompress(InputStream in, String contentEncoding) throws IOException {
        if (contentEncoding != null && contentEncoding.trim().equalsIgnoreCase("gzip")) {
            return new GZIPInputStream(in);
        }
        return in;
    }

    private static boolean isFollowable(int status) {
        return status == 301 || status == 302 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && portOf(a) == portOf(b);
    }

    private static int portOf(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    // ---- error messages ------------------------------------------------------------------------------------------

    static TransportException describe(IOException e, URI uri, TransportOptions options) {
        String hostPort = uri.getHost() + ":" + portOf(uri);
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String message;

        if (e instanceof ResponseTooLargeException) {
            message = detail + " (the response was discarded)";
        } else if (e instanceof UnknownHostException) {
            message = "Unknown host '" + uri.getHost() + "' (DNS lookup failed)";
        } else if (e instanceof SocketTimeoutException) {
            if (detail.toLowerCase(Locale.ROOT).contains("connect")) {
                message = "Timed out connecting to " + hostPort + " after " + options.connectTimeoutSeconds
                        + " s (see --connect-timeout)";
            } else {
                message = "The server did not answer within " + options.readTimeoutSeconds
                        + " s (see --timeout)";
            }
        } else if (e instanceof ConnectException) {
            message = "Could not connect to " + hostPort + ": " + detail;
        } else if (e instanceof SSLException) {
            message = describeTls(e, hostPort, detail);
        } else if (detail.contains("Unable to tunnel through proxy")) {
            message = detail.contains("407")
                    ? "The proxy requires authentication, or rejected the credentials (HTTP 407). "
                    + "Check --proxy-user / --proxy-password. " + "(" + detail + ")"
                    : detail;
        } else {
            message = e.getClass().getSimpleName() + ": " + detail;
        }
        return new TransportException(message, e);
    }

    private static String describeTls(Throwable e, String hostPort, String detail) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            String msg = String.valueOf(t.getMessage());
            if (name.endsWith("ValidatorException") || name.endsWith("SunCertPathBuilderException")
                    || msg.contains("PKIX path")) {
                return "TLS handshake with " + hostPort + " failed: the server certificate is not trusted. "
                        + "Trust its CA with --truststore <file>, or use --insecure for a throw-away test. "
                        + "(" + detail + ")";
            }
            if (msg.contains("No subject alternative names") || msg.contains("No name matching")
                    || msg.contains("doesn't match")) {
                return "TLS handshake with " + hostPort + " failed: the certificate does not match the host name. "
                        + "(" + msg + ")";
            }
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        if (lower.contains("bad_certificate") || lower.contains("certificate_required")
                || lower.contains("handshake_failure") || lower.contains("unknown_ca")) {
            return "TLS handshake with " + hostPort + " failed. The server may require a client certificate "
                    + "(--keystore) or not trust the one you sent. (" + detail + ")";
        }
        return "TLS error talking to " + hostPort + ": " + detail;
    }
}
