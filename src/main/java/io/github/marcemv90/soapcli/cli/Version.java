package io.github.marcemv90.soapcli.cli;

/** The version stamped into the jar manifest by the build ({@code -Drevision=...}). */
final class Version {

    private Version() {
    }

    static String get() {
        String v = Version.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    static String userAgent() {
        return "SoapCLI/" + get();
    }
}
