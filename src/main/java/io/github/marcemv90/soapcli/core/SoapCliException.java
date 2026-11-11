package io.github.marcemv90.soapcli.core;

/**
 * Base class for every error SoapCLI reports on purpose (as opposed to bugs).
 *
 * <p>The subclasses carry the <em>category</em> of the failure so that callers (the CLI today, a web UI later)
 * can decide how to present it without parsing messages.
 */
public class SoapCliException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SoapCliException(String message) {
        super(message);
    }

    public SoapCliException(String message, Throwable cause) {
        super(message, cause);
    }
}
