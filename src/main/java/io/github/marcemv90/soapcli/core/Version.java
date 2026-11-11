package io.github.marcemv90.soapcli.core;

/** The version stamped into the jar manifest by the build ({@code -Drevision=...}). */
public final class Version {

    private Version() {
    }

    /** The version, or {@code dev} when running from compiled classes instead of the jar. */
    public static String get() {
        String v = Version.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    public static String userAgent() {
        return "SoapCLI/" + get();
    }
}
