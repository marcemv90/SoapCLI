package io.github.marcemv90.soapcli.cli;

/**
 * Process exit codes. They deliberately do not mirror the HTTP status: exit codes are 8 bits wide on Linux and
 * macOS, so a 500 turned into 244 and a 404 into 148.
 */
final class ExitCodes {

    /** The server answered with a 2xx status. */
    static final int OK = 0;
    /** Bad command line, profile, file or keystore: nothing was sent. */
    static final int USAGE = 1;
    /** The request could not be delivered (DNS, connection, timeout, TLS, proxy). */
    static final int NETWORK = 2;
    /** The server answered, but not with 2xx (4xx, 5xx including SOAP faults, or a redirect that was not followed). */
    static final int HTTP = 3;

    private ExitCodes() {
    }
}
