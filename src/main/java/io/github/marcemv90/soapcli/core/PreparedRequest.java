package io.github.marcemv90.soapcli.core;

import java.net.URI;
import java.nio.charset.Charset;
import java.util.List;

/**
 * A fully resolved HTTP request: what {@link SoapClient} sends and what previews (dry-run, debug, a UI) show.
 * Both read the same object, so they cannot drift apart.
 */
public final class PreparedRequest {

    public final URI endpoint;
    public final SoapVersion soapVersion;
    public final Charset charset;
    /** Headers exactly as they will be sent, except Content-Length which is computed from the body. */
    public final HeaderMap headers;
    public final RequestBody body;
    /** The SOAP document as sent (xop:Include elements for MTOM), with attachment data replaced by a short note. */
    public final String previewXml;
    public final List<Attachment> attachments;
    public final boolean mtom;
    /** Things the user probably wants to know ("--arg X is not used", "assuming SOAP 1.2"...). */
    public final List<String> warnings;

    PreparedRequest(URI endpoint, SoapVersion soapVersion, Charset charset, HeaderMap headers, RequestBody body,
                    String previewXml, List<Attachment> attachments, boolean mtom, List<String> warnings) {
        this.endpoint = endpoint;
        this.soapVersion = soapVersion;
        this.charset = charset;
        this.headers = headers;
        this.body = body;
        this.previewXml = previewXml;
        this.attachments = attachments;
        this.mtom = mtom;
        this.warnings = warnings;
    }
}
