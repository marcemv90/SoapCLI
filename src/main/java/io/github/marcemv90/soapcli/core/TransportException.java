package io.github.marcemv90.soapcli.core;

/** The request could not be delivered or the response could not be read (DNS, connect, timeout, TLS, proxy...). */
public class TransportException extends SoapCliException {

    private static final long serialVersionUID = 1L;

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
