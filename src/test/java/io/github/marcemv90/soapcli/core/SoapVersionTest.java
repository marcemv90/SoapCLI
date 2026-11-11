package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SoapVersionTest {

    @Test
    void detectsSoap11() {
        assertEquals(SoapVersion.SOAP_1_1, SoapVersion.detect(
                "<?xml version=\"1.0\"?><soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"><soapenv:Body/></soapenv:Envelope>"));
    }

    @Test
    void detectsSoap12WithAnyPrefixAndQuoteStyle() {
        assertEquals(SoapVersion.SOAP_1_2, SoapVersion.detect(
                "<env:Envelope xmlns:x='urn:x' xmlns:env='http://www.w3.org/2003/05/soap-envelope'/>"));
    }

    @Test
    void detectsDefaultNamespace() {
        assertEquals(SoapVersion.SOAP_1_2,
                SoapVersion.detect("<Envelope xmlns=\"http://www.w3.org/2003/05/soap-envelope\"></Envelope>"));
    }

    @Test
    void unknownWhenNotAnEnvelopeOrUnknownNamespace() {
        assertNull(SoapVersion.detect("<a/>"));
        assertNull(SoapVersion.detect("<s:Envelope xmlns:s=\"urn:other\"/>"));
        assertNull(SoapVersion.detect("not xml"));
        assertNull(SoapVersion.detect(""));
    }

    @Test
    void parse() {
        assertEquals(SoapVersion.SOAP_1_1, SoapVersion.parse("1.1"));
        assertEquals(SoapVersion.SOAP_1_2, SoapVersion.parse(" 1.2 "));
        assertNull(SoapVersion.parse("auto"));
        assertThrows(ConfigException.class, () -> SoapVersion.parse("2.0"));
    }
}
