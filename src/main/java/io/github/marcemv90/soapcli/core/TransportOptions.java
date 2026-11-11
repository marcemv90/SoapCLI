package io.github.marcemv90.soapcli.core;

/** Everything about <em>how</em> the request travels, as opposed to what it contains. */
public final class TransportOptions {

    public int connectTimeoutSeconds = 30;
    /** Time to wait for the server to answer. SOAP operations can be slow, so the default is generous. */
    public int readTimeoutSeconds = 120;
    /** Follow 301/302/307/308 by re-sending the same POST. Off by default: silently changing URL is surprising. */
    public boolean followRedirects;
    /** If set, only these destination hosts may be called, including after redirects. {@code null} = no restriction. */
    public HostPolicy hostPolicy;
    /** Refuse responses larger than this many bytes. {@code 0} (the default) means no limit. */
    public long maxResponseBytes;
    public final TlsConfig tls = new TlsConfig();
    public final ProxyConfig proxy = new ProxyConfig();

    public void validate() {
        if (connectTimeoutSeconds < 0 || readTimeoutSeconds < 0) {
            throw new ConfigException("Timeouts must be zero (no timeout) or positive");
        }
        if (maxResponseBytes < 0) {
            throw new ConfigException("The response size limit must be zero (no limit) or positive");
        }
        tls.validate();
        proxy.validate();
    }
}
