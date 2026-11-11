package io.github.marcemv90.soapcli.core;

/** The destination host is not allowed by the configured {@link HostPolicy}. Nothing was sent. */
public class HostNotAllowedException extends ConfigException {

    private static final long serialVersionUID = 1L;

    public HostNotAllowedException(String message) {
        super(message);
    }
}
