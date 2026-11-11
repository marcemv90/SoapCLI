package io.github.marcemv90.soapcli.cli;

import java.util.Locale;

/** ANSI 256-colour palettes for XML output. */
enum ColorTheme {
    MONOKAI("\u001B[38;5;148m", "\u001B[38;5;81m", "\u001B[38;5;208m"),
    SOLARIZED_DARK("\u001B[38;5;109m", "\u001B[38;5;136m", "\u001B[38;5;166m"),
    SOLARIZED_LIGHT("\u001B[38;5;24m", "\u001B[38;5;60m", "\u001B[38;5;166m"),
    DARCULA("\u001B[38;5;81m", "\u001B[38;5;113m", "\u001B[38;5;179m"),
    GRUVBOX_DARK("\u001B[38;5;142m", "\u001B[38;5;109m", "\u001B[38;5;208m"),
    GRUVBOX_LIGHT("\u001B[38;5;24m", "\u001B[38;5;137m", "\u001B[38;5;166m"),
    DRACULA("\u001B[38;5;170m", "\u001B[38;5;117m", "\u001B[38;5;84m"),
    NORD("\u001B[38;5;110m", "\u001B[38;5;109m", "\u001B[38;5;145m");

    static final String RESET = "\u001B[0m";
    static final String COMMENT = "\u001B[38;5;244m";

    final String tag;
    final String attrName;
    final String attrValue;

    ColorTheme(String tag, String attrName, String attrValue) {
        this.tag = tag;
        this.attrName = attrName;
        this.attrValue = attrValue;
    }

    /** Accepts {@code solarized-dark}, {@code solarized_dark} and {@code solarizeddark}, in any case. */
    static ColorTheme parse(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (ColorTheme t : values()) {
            if (t.name().toLowerCase(Locale.ROOT).replace("_", "").equals(v)) {
                return t;
            }
        }
        throw new UsageException("Unknown color theme '" + value + "'. Supported themes: monokai, solarized-dark, "
                + "solarized-light, darcula, gruvbox-dark, gruvbox-light, dracula, nord");
    }
}
