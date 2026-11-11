package io.github.marcemv90.soapcli.cli;

import io.github.marcemv90.soapcli.core.Attachment;
import io.github.marcemv90.soapcli.core.CallConfig;
import io.github.marcemv90.soapcli.core.PreparedRequest;
import io.github.marcemv90.soapcli.core.ProxyConfig;
import io.github.marcemv90.soapcli.core.SecretMasker;
import io.github.marcemv90.soapcli.core.TlsConfig;
import io.github.marcemv90.soapcli.core.XmlFormat;
import java.io.PrintStream;
import java.util.Map;

/**
 * Prints what is about to be sent. It reads the same {@link PreparedRequest} the client sends, so what you see is
 * what goes out, and it never prints credentials unless asked to.
 */
final class RequestPreview {

    private RequestPreview() {
    }

    static void print(PrintStream ps, String title, String endTitle, PreparedRequest request, CallConfig config,
                      boolean showSecrets, boolean color, ColorTheme theme) {
        ps.println(title);
        ps.println("Endpoint: " + request.endpoint);
        ps.println("SOAP version: " + request.soapVersion.label);

        ProxyConfig proxy = config.transport.proxy;
        if (proxy.isConfigured()) {
            ps.println();
            ps.println("Proxy:");
            line(ps, "type", proxy.type);
            line(ps, "host", proxy.host);
            line(ps, "port", proxy.port == null ? null : String.valueOf(proxy.port));
            line(ps, "user", proxy.user);
            if (proxy.password != null) {
                line(ps, "password", showSecrets ? proxy.password : SecretMasker.MASK);
            }
        }

        TlsConfig tls = config.transport.tls;
        if (tls.isCustom()) {
            ps.println();
            ps.println("TLS:");
            line(ps, "keystore", tls.keystorePath);
            line(ps, "keystore alias", tls.keystoreAlias);
            line(ps, "truststore", tls.truststorePath);
            if (tls.insecure) {
                line(ps, "verification", "DISABLED (--insecure)");
            }
        }

        if (!request.attachments.isEmpty()) {
            ps.println();
            ps.println("Attachments (" + (request.mtom ? "MTOM/XOP parts" : "inline Base64") + "):");
            for (Attachment a : request.attachments) {
                ps.println("  cid=" + a.cid + ", file=" + a.file + ", size=" + a.size() + " bytes");
            }
        }

        ps.println();
        ps.println("HTTP headers:");
        for (Map.Entry<String, String> h : request.headers.entries()) {
            ps.println(h.getKey() + ": " + (showSecrets ? h.getValue() : SecretMasker.maskHeader(h.getKey(), h.getValue())));
        }
        ps.println("Content-Length: " + request.body.contentLength());

        String xml = XmlFormat.prettyPrint(request.previewXml);
        if (color) {
            xml = AnsiColorizer.colorize(xml, theme);
        }
        ps.println();
        ps.println("Request body" + (request.mtom ? " (SOAP part of the multipart message):" : ":"));
        ps.print(xml);
        if (!xml.endsWith("\n")) {
            ps.println();
        }
        ps.println(endTitle);
    }

    private static void line(PrintStream ps, String key, String value) {
        if (value != null) {
            ps.println("  " + key + ": " + value);
        }
    }
}
