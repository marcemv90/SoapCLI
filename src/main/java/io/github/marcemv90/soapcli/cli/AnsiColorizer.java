package io.github.marcemv90.soapcli.cli;

import io.github.marcemv90.soapcli.core.XmlScanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adds ANSI colours to XML. Built on {@link XmlScanner}, so {@code >} inside attribute values, comments and CDATA
 * cannot confuse it, and removing the escape sequences always gives back exactly the original text.
 */
final class AnsiColorizer {

    private static final Pattern ATTRIBUTE = Pattern.compile("([^\\s=]+)(\\s*=\\s*)(\"[^\"]*\"|'[^']*')");

    private AnsiColorizer() {
    }

    static String colorize(String xml, ColorTheme theme) {
        if (xml == null || xml.isEmpty()) {
            return xml;
        }
        StringBuilder out = new StringBuilder(xml.length() * 2);
        for (XmlScanner.Token token : XmlScanner.scan(xml)) {
            String text = token.text();
            switch (token.type) {
                case TAG:
                    tag(out, token, text, theme);
                    break;
                case COMMENT:
                    out.append(ColorTheme.COMMENT).append(text).append(ColorTheme.RESET);
                    break;
                case CDATA:
                    out.append(theme.tag).append("<![CDATA[").append(ColorTheme.RESET)
                            .append(text, 9, text.length() - 3)
                            .append(theme.tag).append("]]>").append(ColorTheme.RESET);
                    break;
                case PI:
                case DOCTYPE:
                    out.append(theme.tag).append(text).append(ColorTheme.RESET);
                    break;
                default:
                    out.append(text);
            }
        }
        return out.toString();
    }

    private static void tag(StringBuilder out, XmlScanner.Token token, String text, ColorTheme theme) {
        if (token.isEndTag()) {
            out.append(theme.tag).append(text).append(ColorTheme.RESET);
            return;
        }
        String name = token.tagName();
        int nameEnd = 1 + name.length();
        int closeLength = token.isSelfClosing() ? 2 : 1;
        String attributes = text.substring(nameEnd, text.length() - closeLength);

        out.append(theme.tag).append('<').append(name).append(ColorTheme.RESET);
        Matcher m = ATTRIBUTE.matcher(attributes);
        int last = 0;
        while (m.find()) {
            out.append(attributes, last, m.start())
                    .append(theme.attrName).append(m.group(1)).append(ColorTheme.RESET)
                    .append(m.group(2))
                    .append(theme.attrValue).append(m.group(3)).append(ColorTheme.RESET);
            last = m.end();
        }
        out.append(attributes, last, attributes.length());
        out.append(theme.tag).append(text, text.length() - closeLength, text.length()).append(ColorTheme.RESET);
    }
}
