package io.github.marcemv90.soapcli.core;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which character set a chunk of XML bytes is in, the way an XML parser would:
 * byte order mark, then (for HTTP responses) the {@code charset} of the Content-Type, then the XML declaration,
 * then UTF-8.
 */
public final class XmlCharsets {

    /** The decoded text plus the charset that was used, so it can be re-encoded the same way. */
    public static final class Decoded {
        public final String text;
        public final Charset charset;

        Decoded(String text, Charset charset) {
            this.text = text;
            this.charset = charset;
        }
    }

    private static final Pattern XML_DECL = Pattern.compile(
            "^\\s*<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z0-9._\\-]+)[\"']");
    private static final Pattern CT_CHARSET = Pattern.compile(
            "charset\\s*=\\s*\"?([A-Za-z0-9._\\-:]+)\"?", Pattern.CASE_INSENSITIVE);

    private XmlCharsets() {
    }

    /**
     * @param bytes       the raw bytes
     * @param contentType the HTTP Content-Type header, or {@code null} (e.g. when reading a file)
     */
    public static Decoded decode(byte[] bytes, String contentType) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new Decoded(new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE), StandardCharsets.UTF_16BE);
        }
        if (bytes.length >= 2 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
            return new Decoded(new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE), StandardCharsets.UTF_16LE);
        }

        Charset charset = charsetOf(contentType);
        if (charset == null) {
            charset = sniffDeclaration(bytes);
        }
        if (charset == null) {
            charset = StandardCharsets.UTF_8;
        }
        return new Decoded(new String(bytes, charset), charset);
    }

    /**
     * The encoding an XML text declares in its prolog, or UTF-8. Use it when the text was already decoded (for
     * instance, typed in a browser) and must be sent in the encoding it claims.
     */
    public static Charset declared(String text) {
        if (text == null) {
            return StandardCharsets.UTF_8;
        }
        Matcher m = XML_DECL.matcher(text.length() > 256 ? text.substring(0, 256) : text);
        Charset charset = m.find() ? forName(m.group(1)) : null;
        return charset != null ? charset : StandardCharsets.UTF_8;
    }

    /** The {@code charset} parameter of a Content-Type value, or {@code null} if absent or unsupported. */
    public static Charset charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher m = CT_CHARSET.matcher(contentType);
        return m.find() ? forName(m.group(1)) : null;
    }

    private static Charset sniffDeclaration(byte[] bytes) {
        int len = Math.min(bytes.length, 256);
        String head = new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
        Matcher m = XML_DECL.matcher(head);
        return m.find() ? forName(m.group(1)) : null;
    }

    private static Charset forName(String name) {
        try {
            return Charset.forName(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) { // unsupported or illegal name
            return null;
        }
    }
}
