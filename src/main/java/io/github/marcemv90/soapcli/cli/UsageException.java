package io.github.marcemv90.soapcli.cli;

import io.github.marcemv90.soapcli.core.ConfigException;

/** A mistake in the command line itself: the CLI adds a pointer to {@code --help}. */
final class UsageException extends ConfigException {

    private static final long serialVersionUID = 1L;

    UsageException(String message) {
        super(message);
    }
}
