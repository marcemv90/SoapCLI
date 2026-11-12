package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class DotEnvTest {

    @Test
    void readsNameValueLinesAndSkipsCommentsAndBlankLines() {
        Map<String, String> v = DotEnv.parse("# secrets of the team\n\nACC=svc:s3cret\n  SPACED = padded value  \nexport TOKEN=abc123\n");
        assertEquals(3, v.size());
        assertEquals("svc:s3cret", v.get("ACC"));
        assertEquals("padded value", v.get("SPACED"));
        assertEquals("abc123", v.get("TOKEN"));
    }

    @Test
    void quotesKeepSpacesAndHashesAndAnUnquotedValueEndsAtSpaceHash() {
        Map<String, String> v = DotEnv.parse("A=\"two words # not a comment\"\nB='single'\nC=plain # a comment\nD=a#b\nE=\"unterminated");
        assertEquals("two words # not a comment", v.get("A"));
        assertEquals("single", v.get("B"));
        assertEquals("plain", v.get("C"));
        assertEquals("a#b", v.get("D"), "a # that is not preceded by a space belongs to the value");
        assertEquals("\"unterminated", v.get("E"));
    }

    @Test
    void valuesMayContainEqualsSignsAndWindowsLineEndingsAndABomAreHandled() {
        Map<String, String> v = DotEnv.parse("\uFEFFURL=https://x/?a=1&b=2\r\nEMPTY=\r\nLAST=1");
        assertEquals("https://x/?a=1&b=2", v.get("URL"));
        assertEquals("", v.get("EMPTY"));
        assertEquals("1", v.get("LAST"));
    }

    @Test
    void linesThatAreNotNameValueAreIgnoredAndTheLastDefinitionWins() {
        Map<String, String> v = DotEnv.parse("no equals sign\n1BAD=x\nBAD NAME=x\nGOOD=1\nGOOD=2\n");
        assertEquals(1, v.size());
        assertEquals("2", v.get("GOOD"));
        assertTrue(DotEnv.parse(null).isEmpty());
        assertTrue(DotEnv.parse("").isEmpty());
    }
}
