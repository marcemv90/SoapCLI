package io.github.marcemv90.soapcli.core;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Turns a {@link CallConfig} plus the request text into a {@link PreparedRequest}. Pure: touches no network. */
public final class RequestBuilder {

    private static final String ROOT_CONTENT_ID = "<rootpart@soapcli>";
    private static final String ACCEPT = "text/xml, application/soap+xml, */*;q=0.9";

    private RequestBuilder() {
    }

    /**
     * @param requestText    the request XML after decoding
     * @param requestCharset the charset the XML was written in; the request is sent in the same one
     * @param userAgent      value for the User-Agent header
     */
    public static PreparedRequest build(CallConfig cfg, String requestText, Charset requestCharset, String userAgent) {
        cfg.validate();
        List<String> warnings = new ArrayList<>();

        String text = XmlFormat.clean(requestText);
        if (text == null || text.isBlank()) {
            throw new ConfigException("The request is empty");
        }

        Placeholders.Result placeholders = Placeholders.apply(text, cfg.args);
        for (String name : cfg.args.keySet()) {
            if (!placeholders.usedNames.contains(name)) {
                warnings.add("Argument '" + name + "' is not used by any ___" + name + ":default___ placeholder "
                        + "in the request");
            }
        }
        text = placeholders.text;

        SoapVersion version = cfg.soapVersion;
        if (version == null) {
            version = SoapVersion.detect(text);
            if (version == null) {
                version = SoapVersion.SOAP_1_2;
                warnings.add("Could not detect the SOAP version from the request; assuming SOAP 1.2 "
                        + "(use --soap-version 1.1 or 1.2 to choose)");
            }
        }

        String action = unquote(cfg.soapAction);
        String charsetName = requestCharset.name().toLowerCase(Locale.ROOT);
        List<Attachment> attachments = resolveAttachments(cfg);
        List<String> cids = new ArrayList<>();
        for (Attachment a : attachments) {
            cids.add(a.cid);
        }

        RequestBody body;
        String contentType;
        String preview;
        boolean mtom = false;

        if (attachments.isEmpty()) {
            if (cfg.mtom) {
                warnings.add("--mtom has no effect without --attachment");
            }
            body = new RequestBody.Bytes(text.getBytes(requestCharset));
            contentType = version.mediaType + "; charset=" + charsetName + actionParameter(version, action);
            preview = text;
        } else if (cfg.mtom) {
            mtom = true;
            CidReplacer.Result xop = CidReplacer.replace(text, cids, CidReplacer::xopInclude, false);
            warnUnreferenced(cids, xop, text, true, warnings);
            String boundary = "----=_soapcli_" + UUID.randomUUID();
            String rootContentType = "application/xop+xml; charset=" + requestCharset.name()
                    + "; type=\"" + version.mediaType + "\"" + actionParameter(version, action);
            body = new MultipartBody(boundary, ROOT_CONTENT_ID, rootContentType, xop.text, requestCharset, attachments);
            contentType = "multipart/related; type=\"application/xop+xml\"; start=\"" + ROOT_CONTENT_ID
                    + "\"; start-info=\"" + version.mediaType + "\"" + actionParameter(version, action)
                    + "; boundary=\"" + boundary + "\"";
            preview = xop.text;
        } else {
            Map<String, Attachment> byCid = new HashMap<>();
            for (Attachment a : attachments) {
                byCid.put(a.cid, a);
            }
            CidReplacer.Result inlined = CidReplacer.replace(text, cids, cid -> base64(byCid.get(cid)), true);
            warnUnreferenced(cids, inlined, text, false, warnings);
            CidReplacer.Result shown = CidReplacer.replace(text, cids, cid -> {
                Attachment a = byCid.get(cid);
                return "[base64 of " + a.file.getFileName() + ", " + a.size() + " bytes]";
            }, true);
            body = new RequestBody.Bytes(inlined.text.getBytes(requestCharset));
            contentType = version.mediaType + "; charset=" + charsetName + actionParameter(version, action);
            preview = shown.text;
        }

        HeaderMap headers = new HeaderMap();
        headers.put("Content-Type", contentType);
        headers.put("Accept", ACCEPT);
        if (version == SoapVersion.SOAP_1_1) {
            // SOAP 1.1 requires the header to be present, even if empty.
            headers.put("SOAPAction", "\"" + (action == null ? "" : action) + "\"");
        }
        headers.put("User-Agent", userAgent);
        if (cfg.authBasic != null) {
            headers.put("Authorization", "Basic "
                    + Base64.getEncoder().encodeToString(cfg.authBasic.getBytes(StandardCharsets.UTF_8)));
        } else if (cfg.authBearer != null) {
            headers.put("Authorization", "Bearer " + cfg.authBearer);
        }
        // User supplied headers go last so they can override any default above.
        for (Map.Entry<String, String> h : cfg.headers.entries()) {
            headers.put(h.getKey(), h.getValue());
        }

        if (cfg.transport.tls.insecure) {
            warnings.add("TLS certificate and host name verification are DISABLED (--insecure). "
                    + "Do not use this against production systems.");
        }

        return new PreparedRequest(cfg.endpointUri(), version, requestCharset, headers, body, preview,
                attachments, mtom, warnings);
    }

    private static String actionParameter(SoapVersion version, String action) {
        if (version != SoapVersion.SOAP_1_2 || action == null) {
            return "";
        }
        return "; action=\"" + action.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unquote(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static List<Attachment> resolveAttachments(CallConfig cfg) {
        List<Attachment> list = new ArrayList<>();
        for (Map.Entry<String, String> e : cfg.attachments.entrySet()) {
            Path file = Paths.get(e.getValue());
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new ConfigException("Attachment file '" + e.getValue() + "' (cid '" + e.getKey()
                        + "') does not exist or is not readable");
            }
            list.add(new Attachment(e.getKey(), file));
        }
        return list;
    }

    private static void warnUnreferenced(List<String> cids, CidReplacer.Result result, String text, boolean mtom,
                                         List<String> warnings) {
        for (String cid : cids) {
            if (result.referenced.contains(cid)) {
                continue;
            }
            boolean mentioned = CidReplacer.mentions(text, cid);
            if (!mentioned) {
                warnings.add("Attachment '" + cid + "' is not referenced as cid:" + cid + " in the request");
            } else if (!mtom) {
                warnings.add("cid:" + cid + " only appears in attributes, which inline mode does not replace "
                        + "(use --mtom to send the file as a MIME part)");
            }
            // MTOM + attribute-only reference is the normal "attachment by href" style: nothing to warn about.
        }
    }

    private static String base64(Attachment a) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(a.file));
        } catch (IOException e) {
            throw new ConfigException("Cannot read attachment '" + a.file + "': " + e.getMessage(), e);
        }
    }
}
