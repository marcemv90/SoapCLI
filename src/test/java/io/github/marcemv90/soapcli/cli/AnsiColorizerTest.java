package io.github.marcemv90.soapcli.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AnsiColorizerTest {

    private static String strip(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    @Test
    void removingTheEscapeCodesGivesBackTheOriginalTextExactly() {
        String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE a>\n<s:E xmlns:s='u' href=\"x>y\"  b = \"2\">\n  <!-- a > b -->\n"
                + "  <v>text &amp; more</v><![CDATA[ <raw> ]]><e/><f a=\"1\"/>\n</s:E>\n";
        for (ColorTheme theme : ColorTheme.values()) {
            assertEquals(xml, strip(AnsiColorizer.colorize(xml, theme)), theme.name());
        }
    }

    @Test
    void tagsAttributesAndValuesGetTheirOwnColours() {
        ColorTheme t = ColorTheme.NORD;
        String out = AnsiColorizer.colorize("<a href=\"x\">hi</a>", t);
        assertTrue(out.contains(t.tag + "<a" + ColorTheme.RESET), out);
        assertTrue(out.contains(t.attrName + "href" + ColorTheme.RESET + "=" + t.attrValue + "\"x\"" + ColorTheme.RESET), out);
        assertTrue(out.contains(">hi" + t.tag + "</a>" + ColorTheme.RESET) || out.contains("hi" + t.tag + "</a>"), out);
    }

    @Test
    void commentsAreDimmedAndTextIsLeftAlone() {
        String out = AnsiColorizer.colorize("<a><!-- c -->txt</a>", ColorTheme.DARCULA);
        assertTrue(out.contains(ColorTheme.COMMENT + "<!-- c -->" + ColorTheme.RESET));
        assertTrue(out.contains(ColorTheme.RESET + "txt" + ColorTheme.DARCULA.tag) || out.contains("txt"));
    }
}
