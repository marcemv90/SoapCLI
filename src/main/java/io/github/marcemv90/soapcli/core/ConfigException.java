package io.github.marcemv90.soapcli.core;

/** Invalid arguments, profiles, files or keystores: something the user can fix locally. */
public class ConfigException extends SoapCliException {

    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
