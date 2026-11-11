package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class XmlCharsetsTest {

    @Test
    void httpCharsetBeatsXmlDeclaration() {
        byte[] bytes = "<a>ñandú</a>".getBytes(StandardCharsets.ISO_8859_1);
        XmlCharsets.Decoded d = XmlCharsets.decode(bytes, "text/xml; charset=ISO-8859-1");
        assertEquals("<a>ñandú</a>", d.text);
        assertEquals(StandardCharsets.ISO_8859_1, d.charset);
    }

    @Test
    void xmlDeclarationIsUsedWhenThereIsNoHttpCharset() {
        byte[] bytes = "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>ñ</a>".getBytes(StandardCharsets.ISO_8859_1);
        XmlCharsets.Decoded d = XmlCharsets.decode(bytes, "text/xml");
        assertEquals("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><a>ñ</a>", d.text);
        assertEquals(StandardCharsets.ISO_8859_1, d.charset);
    }

    @Test
    void utf8IsTheDefault() {
        XmlCharsets.Decoded d = XmlCharsets.decode("<a>ñ€</a>".getBytes(StandardCharsets.UTF_8), null);
        assertEquals("<a>ñ€</a>", d.text);
        assertEquals(StandardCharsets.UTF_8, d.charset);
    }

    @Test
    void utf8BomIsStripped() {
        byte[] body = "<a>x</a>".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        assertEquals("<a>x</a>", XmlCharsets.decode(withBom, null).text);
    }

    @Test
    void utf16WithBom() {
        byte[] bytes = "﻿<a>é</a>".getBytes(StandardCharsets.UTF_16LE);
        assertEquals("<a>é</a>", XmlCharsets.decode(bytes, null).text);
    }

    @Test
    void unknownCharsetNamesFallBack() {
        assertNull(XmlCharsets.charsetOf("text/xml; charset=klingon-9"));
        assertEquals(StandardCharsets.UTF_8, XmlCharsets.charsetOf("text/xml; charset=\"utf-8\""));
        assertNull(XmlCharsets.charsetOf(null));
        assertEquals("<a>x</a>", XmlCharsets.decode("<a>x</a>".getBytes(), "text/xml; charset=klingon-9").text);
    }
}
