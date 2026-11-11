package io.github.marcemv90.soapcli.core;

import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** What the server answered, undecoded: the caller decides how to interpret the bytes. */
public final class SoapResponse {

    public final int status;
    public final String statusMessage;
    /** The URL that finally answered (differs from the request URL only when redirects were followed). */
    public final URI uri;
    public final byte[] body;
    public final long elapsedMillis;
    private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    private final List<Map.Entry<String, String>> headerList = new ArrayList<>();

    SoapResponse(int status, String statusMessage, URI uri, Map<String, List<String>> rawHeaders, byte[] body,
                 long elapsedMillis) {
        this.status = status;
        this.statusMessage = statusMessage == null ? "" : statusMessage;
        this.uri = uri;
        this.body = body;
        this.elapsedMillis = elapsedMillis;
        for (Map.Entry<String, List<String>> e : rawHeaders.entrySet()) {
            if (e.getKey() == null) {
                continue; // the status line
            }
            headers.put(e.getKey(), e.getValue());
            for (String v : e.getValue()) {
                headerList.add(new AbstractMap.SimpleImmutableEntry<>(e.getKey(), v));
            }
        }
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isRedirect() {
        return status >= 300 && status < 400;
    }

    /** First value of a header, or {@code null}. */
    public String header(String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    public String contentType() {
        return header("Content-Type");
    }

    /** Multipart (MTOM) and other non-text responses must not be decoded as text. */
    public boolean isBinary() {
        return isBinaryContentType(contentType());
    }

    public static boolean isBinaryContentType(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase(java.util.Locale.ROOT);
        if (ct.startsWith("multipart/")) {
            return true;
        }
        return !(ct.contains("xml") || ct.startsWith("text/") || ct.contains("json") || ct.contains("html")
                || ct.contains("soap"));
    }

    /** All headers, in the order the server sent them. */
    public List<Map.Entry<String, String>> headerEntries() {
        return headerList;
    }
}
