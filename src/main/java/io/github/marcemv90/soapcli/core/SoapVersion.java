package io.github.marcemv90.soapcli.core;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SOAP 1.1 and 1.2 differ on the wire (Content-Type and where the action goes), so we need to know which one. */
public enum SoapVersion {

    SOAP_1_1("1.1", "http://schemas.xmlsoap.org/soap/envelope/", "text/xml"),
    SOAP_1_2("1.2", "http://www.w3.org/2003/05/soap-envelope", "application/soap+xml");

    private static final Pattern XMLNS = Pattern.compile(
            "\\sxmlns(?::([A-Za-z0-9_.\\-]+))?\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    public final String label;
    public final String namespace;
    public final String mediaType;

    SoapVersion(String label, String namespace, String mediaType) {
        this.label = label;
        this.namespace = namespace;
        this.mediaType = mediaType;
    }

    /**
     * Parses a user supplied value.
     *
     * @return the version, or {@code null} for {@code auto}
     */
    public static SoapVersion parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "1.1":
            case "11":
            case "soap11":
            case "soap1.1":
                return SOAP_1_1;
            case "1.2":
            case "12":
            case "soap12":
            case "soap1.2":
                return SOAP_1_2;
            case "auto":
            case "":
                return null;
            default:
                throw new ConfigException("Invalid SOAP version '" + value + "'. Use 1.1, 1.2 or auto.");
        }
    }

    /**
     * Looks at the namespace of the root {@code Envelope} element.
     *
     * @return the version, or {@code null} if the root is not a recognisable SOAP envelope
     */
    public static SoapVersion detect(String xml) {
        if (xml == null) {
            return null;
        }
        for (XmlScanner.Token t : XmlScanner.scan(xml)) {
            if (t.type != XmlScanner.Type.TAG) {
                continue;
            }
            if (t.isEndTag()) {
                return null;
            }
            String name = t.tagName();
            int colon = name.indexOf(':');
            String prefix = colon < 0 ? "" : name.substring(0, colon);
            String local = colon < 0 ? name : name.substring(colon + 1);
            if (!"Envelope".equals(local)) {
                return null;
            }
            Map<String, String> declared = new HashMap<>();
            Matcher m = XMLNS.matcher(t.text());
            while (m.find()) {
                declared.put(m.group(1) == null ? "" : m.group(1), m.group(2) != null ? m.group(2) : m.group(3));
            }
            String ns = declared.get(prefix);
            for (SoapVersion v : values()) {
                if (v.namespace.equals(ns)) {
                    return v;
                }
            }
            return null;
        }
        return null;
    }
}
