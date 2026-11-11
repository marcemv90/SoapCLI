package io.github.marcemv90.soapcli.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Everything the user can say about one SOAP call. It is filled from command-line flags and/or a profile and has no
 * knowledge of terminals: a web UI can fill the very same object from a form.
 */
public final class CallConfig {

    public String endpoint;
    /** Path of the request XML. {@code null} or {@code -} means "read it from standard input". */
    public String requestFile;

    public final HeaderMap headers = new HeaderMap();
    /** Values for {@code ___NAME:default___} placeholders (already XML-escaped if the user asked for it). */
    public final Map<String, String> args = new LinkedHashMap<>();
    /** CID to file path. */
    public final Map<String, String> attachments = new LinkedHashMap<>();
    public boolean mtom;

    /** {@code null} means "detect it from the envelope". */
    public SoapVersion soapVersion;
    public String soapAction;

    /** {@code user:password}. */
    public String authBasic;
    public String authBearer;

    public final TransportOptions transport = new TransportOptions();

    /** Parses and checks the endpoint. */
    public URI endpointUri() {
        if (endpoint == null) {
            throw new ConfigException("Missing endpoint: use --endpoint <URL> or a profile that defines one");
        }
        URI uri;
        try {
            uri = new URI(endpoint.trim());
        } catch (URISyntaxException e) {
            throw new ConfigException("Invalid endpoint '" + endpoint + "': " + e.getReason(), e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ConfigException("The endpoint must be an http:// or https:// URL, got '" + endpoint + "'");
        }
        if (uri.getHost() == null) {
            throw new ConfigException("The endpoint '" + endpoint + "' has no host");
        }
        return uri;
    }

    /** Fails with a {@link ConfigException} if the combination of settings cannot work. */
    public void validate() {
        endpointUri();
        if (authBasic != null && authBearer != null) {
            throw new ConfigException("Use either basic or bearer authentication, not both");
        }
        if (authBasic != null && authBasic.indexOf(':') < 0) {
            throw new ConfigException("Basic credentials must have the form user:password");
        }
        transport.validate();
    }
}
