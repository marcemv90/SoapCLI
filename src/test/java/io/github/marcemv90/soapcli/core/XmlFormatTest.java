package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class XmlFormatTest {

    @Test
    void indentsNestedElementsAndKeepsTextInline() {
        String out = XmlFormat.prettyPrint("<a><b>1</b><c><d/></c></a>");
        assertEquals("<a>\n  <b>1</b>\n  <c>\n    <d/>\n  </c>\n</a>\n", out);
    }

    @Test
    void alreadyFormattedInputIsStable() {
        String pretty = "<a>\n  <b>1</b>\n  <c>\n    <d/>\n  </c>\n</a>\n";
        assertEquals(pretty, XmlFormat.prettyPrint(pretty));
    }

    @Test
    void declarationCommentsAndNamespacesAreKeptAsWritten() {
        String in = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:E xmlns:s='u'><!-- hi --><s:B a=\"1\"  b='2'>x</s:B></s:E>";
        String out = XmlFormat.prettyPrint(in);
        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<s:E xmlns:s='u'>\n  <!-- hi -->\n"
                + "  <s:B a=\"1\"  b='2'>x</s:B>\n</s:E>\n", out);
    }

    @Test
    void entityReferencesAndCdataAreNotRewritten() {
        String out = XmlFormat.prettyPrint("<a><b>x &amp; y</b><c><![CDATA[ <raw> & ]]></c></a>");
        assertEquals("<a>\n  <b>x &amp; y</b>\n  <c><![CDATA[ <raw> & ]]></c>\n</a>\n", out);
    }

    @Test
    void mixedContentIsLeftUntouched() {
        String out = XmlFormat.prettyPrint("<p>hello <b>big</b> world</p>");
        assertEquals("<p>hello <b>big</b> world</p>\n", out);
    }

    @Test
    void textWithSurroundingWhitespaceIsDataAndKept() {
        assertEquals("<a>\n  <b>  padded  </b>\n</a>\n", XmlFormat.prettyPrint("<a>\n\n  <b>  padded  </b>\n\n</a>"));
    }

    @Test
    void emptyLinesBetweenElementsAreDropped() {
        assertEquals("<a>\n  <b/>\n  <c/>\n</a>\n", XmlFormat.prettyPrint("<a>\r\n\r\n   <b/>\r\n\r\n   <c/>\r\n\r\n</a>"));
    }

    @Test
    void malformedInputIsReturnedUnchanged() {
        String bad = "<a><b></a>";
        assertSame(bad, XmlFormat.prettyPrint(bad));
        String html = "<html><body><br></body></html>";
        assertSame(html, XmlFormat.prettyPrint(html));
        assertSame("plain text", XmlFormat.prettyPrint("plain text"));
    }

    @Test
    void doctypeWithExternalEntityIsOnlyEchoedNeverResolved() {
        // This is the XXE payload that made the old DOM based formatter print the contents of /etc/hostname.
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]><a>&xxe;</a>";
        String out = XmlFormat.prettyPrint(xml);
        assertEquals("<?xml version=\"1.0\"?>\n<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/hostname\">]>\n"
                + "<a>&xxe;</a>\n", out);
    }

    @Test
    void nullAndBlank() {
        assertNull(XmlFormat.prettyPrint(null));
        assertEquals("  ", XmlFormat.prettyPrint("  "));
    }

    @Test
    void cleanRemovesLeadingBomAndIllegalControlCharsOnly() {
        assertEquals("<a>x​y</a>", XmlFormat.clean("﻿<a>x​y</a>")); // zero width space is data
        assertEquals("<a>xy</a>", XmlFormat.clean("<a>x\u0001y</a>"));
        assertEquals("<a>\t\r\n</a>", XmlFormat.clean("<a>\t\r\n</a>"));
        assertFalse(XmlFormat.clean("a﻿b").isEmpty());
        assertEquals("a﻿b", XmlFormat.clean("a﻿b")); // only a LEADING BOM is junk
    }

    @Test
    void escape() {
        assertEquals("Tom &amp; Jerry &lt;3 &quot;q&quot; &apos;s&apos; &gt;", XmlFormat.escape("Tom & Jerry <3 \"q\" 's' >"));
    }
}
