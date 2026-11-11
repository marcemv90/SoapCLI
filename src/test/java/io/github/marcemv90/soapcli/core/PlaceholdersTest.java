package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceholdersTest {

    @Test
    void usesDefaultsWhenNoValueIsGiven() {
        Placeholders.Result r = Placeholders.apply("<a>___ID:42___</a>", Collections.emptyMap());
        assertEquals("<a>42</a>", r.text);
        assertTrue(r.usedNames.isEmpty());
    }

    @Test
    void overridesAndReportsWhichNamesWereUsed() {
        Map<String, String> v = new HashMap<>();
        v.put("ID", "7");
        v.put("TYPO", "x");
        Placeholders.Result r = Placeholders.apply("<a>___ID:42___</a>", v);
        assertEquals("<a>7</a>", r.text);
        assertEquals(Collections.singleton("ID"), r.usedNames);
    }

    @Test
    void anOverrideCanBeTheEmptyString() {
        Map<String, String> v = new HashMap<>();
        v.put("ID", "");
        assertEquals("<a></a>", Placeholders.apply("<a>___ID:42___</a>", v).text);
    }

    @Test
    void worksInElementNamesAttributesAndCdata() {
        String xml = "<___TAG:order___ id=\"___ID:1___\"><![CDATA[c=___ID:1___]]></___TAG:order___>";
        Map<String, String> v = new HashMap<>();
        v.put("TAG", "invoice");
        assertEquals("<invoice id=\"1\"><![CDATA[c=1]]></invoice>", Placeholders.apply(xml, v).text);
    }

    @Test
    void aStrayTripleUnderscoreDoesNotSwallowTheDocument() {
        // Regression: the old pattern turned this into "<note>use //x</note><v>V:1___</v>"
        String xml = "<a><note>use ___ as separator, see http://x</note><v>___V:1___</v></a>";
        assertEquals("<a><note>use ___ as separator, see http://x</note><v>1</v></a>",
                Placeholders.apply(xml, Collections.emptyMap()).text);
    }

    @Test
    void valuesAreInsertedLiterally() {
        Map<String, String> v = new HashMap<>();
        v.put("N", "$1 \\ & <b>");
        assertEquals("<a>$1 \\ & <b></a>", Placeholders.apply("<a>___N:x___</a>", v).text);
    }

    @Test
    void multilineDefaults() {
        assertEquals("<a>l1\nl2</a>", Placeholders.apply("<a>___N:l1\nl2___</a>", Collections.emptyMap()).text);
    }
}
