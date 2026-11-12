package io.github.marcemv90.soapcli.cli;

import io.github.marcemv90.soapcli.core.CallConfig;

/** The parsed command line: the call itself plus everything about how to present it. */
final class CliOptions {

    enum ColorMode { AUTO, ALWAYS, NEVER }

    final CallConfig call = new CallConfig();

    ColorTheme theme = ColorTheme.DARCULA;
    ColorMode color = ColorMode.AUTO;
    boolean raw;
    String outputFile;
    boolean include;
    boolean quiet;
    boolean dryRun;
    boolean debug;
    boolean showSecrets;

    String profileName;
    String profileFile;
    String workspace;
    String workspacesDir;

    boolean help;
    boolean version;
    boolean listProfiles;
    boolean listProfilesDetailed;
    String showProfile;

    /** {@code --auth-basic} given explicitly on the command line; it wins over {@code --auth-basic-env}. */
    boolean basicFromFlag;
}
