package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class CidReplacerTest {

    @Test
    void matchesAreExactNotPrefixes() {
        // Regression: in MTOM mode message10 became "message1" + a stray "0".
        String xml = "<p><x>cid:message1</x><y>cid:message10</y><z>cid:message1-b</z></p>";
        CidReplacer.Result r = CidReplacer.replace(xml, Arrays.asList("message1", "message10"), c -> "[" + c + "]", false);
        assertEquals("<p><x>[message1]</x><y>[message10]</y><z>cid:message1-b</z></p>", r.text);
        assertEquals(new HashSet<>(Arrays.asList("message1", "message10")), r.referenced);
    }

    @Test
    void attributesAndCommentsAreNeverTouched() {
        String xml = "<p payloadId=\"cid:a\"><!-- cid:a --><v>cid:a</v></p>";
        assertEquals("<p payloadId=\"cid:a\"><!-- cid:a --><v>X</v></p>",
                CidReplacer.replace(xml, Collections.singletonList("a"), c -> "X", true).text);
    }

    @Test
    void cdataIsOnlySearchedWhenAsked() {
        String xml = "<v><![CDATA[cid:a]]></v>";
        assertEquals(xml, CidReplacer.replace(xml, Collections.singletonList("a"), c -> "X", false).text);
        assertEquals("<v><![CDATA[X]]></v>", CidReplacer.replace(xml, Collections.singletonList("a"), c -> "X", true).text);
    }

    @Test
    void theReplacementIsComputedOncePerCid() {
        int[] calls = {0};
        CidReplacer.replace("<a>cid:x</a><b>cid:x</b>", Collections.singletonList("x"), c -> {
            calls[0]++;
            return "Z";
        }, true);
        assertEquals(1, calls[0]);
    }

    @Test
    void replacementTextIsNotRescanned() {
        // The XOP element itself contains "cid:m" and must not be replaced again.
        String xml = "<a>cid:m</a>";
        CidReplacer.Result r = CidReplacer.replace(xml, Arrays.asList("m", "x"), CidReplacer::xopInclude, false);
        assertEquals("<a><inc:Include href=\"cid:m\" xmlns:inc=\"http://www.w3.org/2004/08/xop/include\"/></a>", r.text);
    }

    @Test
    void cidsWithRegexMetaCharactersAreQuoted() {
        CidReplacer.Result r = CidReplacer.replace("<a>cid:part.1@host</a>", Collections.singletonList("part.1@host"),
                c -> "OK", false);
        assertEquals("<a>OK</a>", r.text);
        assertEquals("<a>cid:partX1@host</a>",
                CidReplacer.replace("<a>cid:partX1@host</a>", Collections.singletonList("part.1@host"), c -> "OK", false).text);
    }

    @Test
    void mentionsFindsAttributeOnlyReferences() {
        assertTrue(CidReplacer.mentions("<p href=\"cid:a\"/>", "a"));
        assertFalse(CidReplacer.mentions("<p href=\"cid:ab\"/>", "a"));
    }

    @Test
    void textBoundaryRespectsTheNextTag() {
        assertEquals("<a>X</a>", CidReplacer.replace("<a>cid:a</a>", Collections.singletonList("a"), c -> "X", false).text);
    }
}
