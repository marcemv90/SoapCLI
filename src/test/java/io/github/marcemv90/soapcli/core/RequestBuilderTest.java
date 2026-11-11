package io.github.marcemv90.soapcli.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RequestBuilderTest {

    static final String ENV11 = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><ping/></s:Body></s:Envelope>";
    static final String ENV12 = "<s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Body><ping/></s:Body></s:Envelope>";

    private static CallConfig cfg() {
        CallConfig c = new CallConfig();
        c.endpoint = "http://localhost:1/soap";
        return c;
    }

    private static PreparedRequest build(CallConfig c, String xml) {
        return RequestBuilder.build(c, xml, StandardCharsets.UTF_8, "test-agent");
    }

    private static byte[] bytes(RequestBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        body.writeTo(out);
        assertEquals(out.size(), body.contentLength(), "declared Content-Length must match the bytes written");
        return out.toByteArray();
    }

    @Test
    void soap12IsTheDefaultOnTheWire() {
        PreparedRequest r = build(cfg(), ENV12);
        assertEquals(SoapVersion.SOAP_1_2, r.soapVersion);
        assertEquals("application/soap+xml; charset=utf-8", r.headers.get("Content-Type"));
        assertFalse(r.headers.contains("SOAPAction"));
        assertEquals("test-agent", r.headers.get("User-Agent"));
        assertTrue(r.warnings.isEmpty());
    }

    @Test
    void soap11IsDetectedAndGetsTextXmlAndAnEmptySoapActionHeader() {
        PreparedRequest r = build(cfg(), ENV11);
        assertEquals(SoapVersion.SOAP_1_1, r.soapVersion);
        assertEquals("text/xml; charset=utf-8", r.headers.get("Content-Type"));
        assertEquals("\"\"", r.headers.get("SOAPAction"));
    }

    @Test
    void soapActionGoesInTheHeaderFor11AndInTheContentTypeFor12() {
        CallConfig c = cfg();
        c.soapAction = "urn:doIt";
        assertEquals("\"urn:doIt\"", build(c, ENV11).headers.get("SOAPAction"));
        assertEquals("application/soap+xml; charset=utf-8; action=\"urn:doIt\"", build(c, ENV12).headers.get("Content-Type"));
        c.soapAction = "\"urn:quoted\"";
        assertEquals("\"urn:quoted\"", build(c, ENV11).headers.get("SOAPAction"));
    }

    @Test
    void explicitVersionBeatsDetection() {
        CallConfig c = cfg();
        c.soapVersion = SoapVersion.SOAP_1_1;
        assertEquals("text/xml; charset=utf-8", build(c, ENV12).headers.get("Content-Type"));
    }

    @Test
    void undetectableVersionWarnsAndFallsBackTo12() {
        PreparedRequest r = build(cfg(), "<a/>");
        assertEquals(SoapVersion.SOAP_1_2, r.soapVersion);
        assertEquals(1, r.warnings.size());
        assertTrue(r.warnings.get(0).contains("SOAP 1.2"));
    }

    @Test
    void userHeadersOverrideDefaults() {
        CallConfig c = cfg();
        c.headers.put("content-type", "text/xml");
        c.headers.put("X-Env", "acc");
        PreparedRequest r = build(c, ENV12);
        assertEquals("text/xml", r.headers.get("Content-Type"));
        assertEquals("acc", r.headers.get("X-Env"));
    }

    @Test
    void basicAndBearerAuth() {
        CallConfig c = cfg();
        c.authBasic = "alice:pä:ss";
        assertEquals("Basic " + Base64.getEncoder().encodeToString("alice:pä:ss".getBytes(StandardCharsets.UTF_8)),
                build(c, ENV12).headers.get("Authorization"));
        c.authBasic = null;
        c.authBearer = "tok";
        assertEquals("Bearer tok", build(c, ENV12).headers.get("Authorization"));
    }

    @Test
    void invalidCombinationsAreRejected() {
        CallConfig both = cfg();
        both.authBasic = "a:b";
        both.authBearer = "t";
        assertThrows(ConfigException.class, () -> build(both, ENV12));

        CallConfig noColon = cfg();
        noColon.authBasic = "justuser";
        assertThrows(ConfigException.class, () -> build(noColon, ENV12));

        CallConfig fileScheme = cfg();
        fileScheme.endpoint = "file:///etc/hostname";
        assertThrows(ConfigException.class, () -> build(fileScheme, ENV12));

        CallConfig noHost = cfg();
        noHost.endpoint = "http:///x";
        assertThrows(ConfigException.class, () -> build(noHost, ENV12));

        CallConfig none = new CallConfig();
        assertThrows(ConfigException.class, () -> build(none, ENV12));

        assertThrows(ConfigException.class, () -> build(cfg(), "  \n"));
    }

    @Test
    void placeholdersAreAppliedAndUnusedArgumentsAreReported() throws IOException {
        CallConfig c = cfg();
        c.args.put("ID", "99");
        c.args.put("IDD", "typo");
        PreparedRequest r = build(c, ENV12.replace("<ping/>", "<ping>___ID:1___</ping>"));
        assertTrue(new String(bytes(r.body), StandardCharsets.UTF_8).contains("<ping>99</ping>"));
        assertEquals(1, r.warnings.size());
        assertTrue(r.warnings.get(0).contains("IDD"));
    }

    @Test
    void requestIsSentInItsOwnCharset() throws IOException {
        PreparedRequest r = RequestBuilder.build(cfg(), "<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>" + ENV12.replace("<ping/>", "<n>ñ</n>"),
                StandardCharsets.ISO_8859_1, "ua");
        assertEquals("application/soap+xml; charset=iso-8859-1", r.headers.get("Content-Type"));
        byte[] sent = bytes(r.body);
        assertTrue(new String(sent, StandardCharsets.ISO_8859_1).contains("<n>ñ</n>"));
        assertEquals(1, new String(sent, StandardCharsets.ISO_8859_1).chars().filter(ch -> ch == 0xF1).count());
    }

    @Test
    void inlineAttachmentsReplaceTextOnlyAndPreviewHidesTheData(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.bin");
        Files.write(file, "hello".getBytes(StandardCharsets.UTF_8));
        CallConfig c = cfg();
        c.attachments.put("message", file.toString());
        String xml = ENV12.replace("<ping/>", "<payload payloadId=\"cid:message\"><value>cid:message</value></payload>");

        PreparedRequest r = build(c, xml);
        String sent = new String(bytes(r.body), StandardCharsets.UTF_8);
        assertTrue(sent.contains("payloadId=\"cid:message\""));
        assertTrue(sent.contains("<value>" + Base64.getEncoder().encodeToString("hello".getBytes()) + "</value>"));
        assertFalse(r.mtom);
        assertTrue(r.previewXml.contains("[base64 of a.bin, 5 bytes]"));
        assertFalse(r.previewXml.contains("aGVsbG8"));
    }

    @Test
    void mtomBuildsAMultipartWithXopIncludesAndExactCids(@TempDir Path dir) throws IOException {
        Path f1 = dir.resolve("one.bin");
        Path f10 = dir.resolve("ten.bin");
        Files.write(f1, "hello".getBytes());
        Files.write(f10, "WORLD".getBytes());
        CallConfig c = cfg();
        c.mtom = true;
        c.attachments.put("message1", f1.toString());
        c.attachments.put("message10", f10.toString());

        PreparedRequest r = build(c, ENV12.replace("<ping/>", "<x>cid:message1</x><y>cid:message10</y>"));
        assertTrue(r.mtom);
        String ct = r.headers.get("Content-Type");
        assertTrue(ct.startsWith("multipart/related; type=\"application/xop+xml\"; start=\"<rootpart@soapcli>\""), ct);
        assertTrue(ct.contains("start-info=\"application/soap+xml\""), ct);
        assertFalse(ct.contains("action"), "no hard coded action any more: " + ct);

        String body = new String(bytes(r.body), StandardCharsets.ISO_8859_1);
        String boundary = ct.substring(ct.indexOf("boundary=\"") + 10, ct.lastIndexOf('"'));
        assertTrue(body.startsWith("--" + boundary + "\r\n"));
        assertTrue(body.endsWith("--" + boundary + "--\r\n"));
        // Regression: message10 must not become message1 + "0".
        assertTrue(body.contains("<y><inc:Include href=\"cid:message10\""), body);
        assertFalse(body.contains("/>0</y>"));
        assertTrue(body.contains("Content-ID: <message1>\r\nContent-Transfer-Encoding: binary\r\n\r\nhello\r\n"));
        assertTrue(body.contains("Content-ID: <message10>\r\nContent-Transfer-Encoding: binary\r\n\r\nWORLD\r\n"));
        assertFalse(body.contains("submitMessage"));
        assertFalse(body.contains("soapui"));
    }

    @Test
    void mtomActionComesFromSoapActionForSoap12AndIsOmittedForSoap11(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.bin");
        Files.write(f, "x".getBytes());
        CallConfig c = cfg();
        c.mtom = true;
        c.soapAction = "submitMessage";
        c.attachments.put("m", f.toString());
        String xml12 = ENV12.replace("<ping/>", "<v>cid:m</v>");
        PreparedRequest r12 = build(c, xml12);
        assertTrue(r12.headers.get("Content-Type").contains("; action=\"submitMessage\""));
        assertTrue(new String(bytes(r12.body), StandardCharsets.ISO_8859_1)
                .contains("type=\"application/soap+xml\"; action=\"submitMessage\""));

        PreparedRequest r11 = build(c, ENV11.replace("<ping/>", "<v>cid:m</v>"));
        assertTrue(r11.headers.get("Content-Type").contains("start-info=\"text/xml\""));
        assertFalse(r11.headers.get("Content-Type").contains("action="));
        assertEquals("\"submitMessage\"", r11.headers.get("SOAPAction"));
    }

    @Test
    void attachmentWarnings(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("a.bin");
        Files.write(f, "x".getBytes());

        CallConfig inline = cfg();
        inline.attachments.put("ghost", f.toString());
        PreparedRequest unreferenced = build(inline, ENV12);
        assertTrue(unreferenced.warnings.get(0).contains("not referenced"));

        CallConfig attrOnly = cfg();
        attrOnly.attachments.put("m", f.toString());
        String attrXml = ENV12.replace("<ping/>", "<p href=\"cid:m\"/>");
        assertTrue(build(attrOnly, attrXml).warnings.get(0).contains("only appears in attributes"));

        attrOnly.mtom = true; // attachment-by-href is the normal MTOM/SwA style: no warning
        assertTrue(build(attrOnly, attrXml).warnings.isEmpty());

        CallConfig mtomNoFiles = cfg();
        mtomNoFiles.mtom = true;
        assertTrue(build(mtomNoFiles, ENV12).warnings.get(0).contains("--mtom has no effect"));
    }

    @Test
    void missingAttachmentFileIsAConfigError() {
        CallConfig c = cfg();
        c.attachments.put("x", "/definitely/not/here.bin");
        assertThrows(ConfigException.class, () -> build(c, ENV12));
    }

    @Test
    void insecureTlsAlwaysWarns() {
        CallConfig c = cfg();
        c.transport.tls.insecure = true;
        assertTrue(build(c, ENV12).warnings.get(0).contains("DISABLED"));
        assertNull(build(cfg(), ENV12).headers.get("X-Nope"));
    }
}
