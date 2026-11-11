package io.github.marcemv90.soapcli.cli;

import io.github.marcemv90.soapcli.core.SecretMasker;
import io.github.marcemv90.soapcli.core.SoapResponse;
import io.github.marcemv90.soapcli.core.XmlCharsets;
import io.github.marcemv90.soapcli.core.XmlFormat;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

/** Presents a {@link SoapResponse}: the body on stdout (or a file), everything else on stderr. */
final class ResponsePrinter {

    private final PrintStream out;
    private final PrintStream err;
    private final boolean color;
    private final ColorTheme theme;

    ResponsePrinter(PrintStream out, PrintStream err, boolean color, ColorTheme theme) {
        this.out = out;
        this.err = err;
        this.color = color;
        this.theme = theme;
    }

    /** One status line, optionally followed by the response headers. */
    void printStatus(SoapResponse r, boolean quiet, boolean headers, boolean showSecrets, boolean followRedirects) {
        if (!quiet) {
            err.println("HTTP " + r.status + (r.statusMessage.isEmpty() ? "" : " " + r.statusMessage)
                    + " (" + r.elapsedMillis + " ms, " + r.body.length + " bytes)");
        }
        if (headers) {
            for (Map.Entry<String, String> h : r.headerEntries()) {
                err.println(h.getKey() + ": " + (showSecrets ? h.getValue() : SecretMasker.maskHeader(h.getKey(), h.getValue())));
            }
            err.println();
        }
        if (r.isRedirect() && !followRedirects) {
            String location = r.header("Location");
            err.println("The server redirected to " + (location == null ? "another URL" : location)
                    + ". The redirect was not followed; use --follow-redirects to follow it.");
        }
    }

    void printBody(SoapResponse r, boolean raw, String outputFile) throws IOException {
        byte[] body = r.body;
        Path file = outputFile == null ? null : Paths.get(outputFile);
        if (body.length == 0) {
            if (file != null) {
                Files.write(file, body);
            }
            return;
        }

        if (raw || isBinary(r.contentType())) {
            if (file != null) {
                Files.write(file, body);
            } else {
                out.write(body, 0, body.length);
            }
            return;
        }

        XmlCharsets.Decoded decoded = XmlCharsets.decode(body, r.contentType());
        String text = XmlFormat.prettyPrint(XmlFormat.clean(decoded.text));
        if (!text.endsWith("\n")) {
            text += "\n";
        }
        if (file != null) {
            // Written in the charset the document declares, so an encoding="ISO-8859-1" prolog stays truthful.
            Files.write(file, text.getBytes(decoded.charset));
        } else {
            out.print(color ? AnsiColorizer.colorize(text, theme) : text);
        }
    }

    /** Multipart (MTOM) and other non-text responses must not be decoded as text. */
    static boolean isBinary(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.startsWith("multipart/")) {
            return true;
        }
        return !(ct.contains("xml") || ct.startsWith("text/") || ct.contains("json") || ct.contains("html")
                || ct.contains("soap"));
    }
}
