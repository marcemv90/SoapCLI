package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class XmlScannerTest {

    private static String rebuild(List<XmlScanner.Token> tokens) {
        StringBuilder sb = new StringBuilder();
        for (XmlScanner.Token t : tokens) {
            sb.append(t.text());
        }
        return sb.toString();
    }

    @Test
    void tokensCoverTheWholeInputExactly() {
        String xml = "<?xml version=\"1.0\"?>\n<!DOCTYPE a [<!ENTITY x \"y>z\">]>\n<a b=\"1>2\" c='3'>text<!-- c > d -->"
                + "<![CDATA[ <not-a-tag> ]]><b/></a>\n";
        assertEquals(xml, rebuild(XmlScanner.scan(xml)));
    }

    @Test
    void greaterThanInsideAttributeValuesDoesNotEndTheTag() {
        List<XmlScanner.Token> tokens = XmlScanner.scan("<a href=\"x>y\">t</a>");
        assertEquals(XmlScanner.Type.TAG, tokens.get(0).type);
        assertEquals("<a href=\"x>y\">", tokens.get(0).text());
        assertEquals("a", tokens.get(0).tagName());
    }

    @Test
    void classifiesTokens() {
        List<XmlScanner.Token> t = XmlScanner.scan("<a><!--c--><![CDATA[d]]><?pi x?>e</a><b/>");
        assertEquals(XmlScanner.Type.TAG, t.get(0).type);
        assertEquals(XmlScanner.Type.COMMENT, t.get(1).type);
        assertEquals(XmlScanner.Type.CDATA, t.get(2).type);
        assertEquals(XmlScanner.Type.PI, t.get(3).type);
        assertEquals(XmlScanner.Type.TEXT, t.get(4).type);
        assertTrue(t.get(5).isEndTag());
        assertTrue(t.get(6).isSelfClosing());
        assertEquals("b", t.get(6).tagName());
    }

    @Test
    void namespacedTagName() {
        assertEquals("soapenv:Envelope", XmlScanner.scan("<soapenv:Envelope xmlns:soapenv=\"u\">").get(0).tagName());
        assertEquals("soapenv:Envelope", XmlScanner.scan("</soapenv:Envelope >").get(0).tagName());
    }

    @Test
    void unterminatedConstructsBecomeText() {
        assertEquals(XmlScanner.Type.TEXT, XmlScanner.scan("<a").get(0).type);
        assertEquals(XmlScanner.Type.TEXT, XmlScanner.scan("<!-- never closed").get(0).type);
        assertEquals("<a", rebuild(XmlScanner.scan("<a")));
    }

    @Test
    void blankText() {
        assertTrue(XmlScanner.scan(" \n\t").get(0).isBlankText());
        assertFalse(XmlScanner.scan(" x ").get(0).isBlankText());
    }
}
