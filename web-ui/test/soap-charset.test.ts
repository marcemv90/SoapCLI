import { describe, expect, it } from "vitest";
import { bytesToBase64, charsetOfContentType, declaredCharset, decodeBytes, encodeText, stringToBase64 } from "../src/lib/charset";
import { detectSoapVersion } from "../src/lib/soap";

describe("detectSoapVersion (same cases as SoapVersionTest.java)", () => {
  it("detects SOAP 1.1", () => {
    expect(detectSoapVersion(`<?xml version="1.0"?><soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"><soapenv:Body/></soapenv:Envelope>`)).toBe("1.1");
  });
  it("detects SOAP 1.2 with any prefix and quote style", () => {
    expect(detectSoapVersion(`<env:Envelope xmlns:x='urn:x' xmlns:env='http://www.w3.org/2003/05/soap-envelope'/>`)).toBe("1.2");
  });
  it("detects a default namespace", () => {
    expect(detectSoapVersion(`<Envelope xmlns="http://www.w3.org/2003/05/soap-envelope"></Envelope>`)).toBe("1.2");
  });
  it("is unknown for anything else", () => {
    for (const x of ["<a/>", `<s:Envelope xmlns:s="urn:other"/>`, "not xml", ""]) expect(detectSoapVersion(x)).toBeNull();
  });
});

describe("charsets (same cases as XmlCharsetsTest.java)", () => {
  const latin1 = (s: string) => Uint8Array.from([...s].map((c) => c.charCodeAt(0)));

  it("prefers the HTTP charset over the XML declaration", () => {
    const d = decodeBytes(latin1("<a>ñandú</a>"), "text/xml; charset=ISO-8859-1");
    expect(d.text).toBe("<a>ñandú</a>");
  });
  it("uses the XML declaration when there is no HTTP charset", () => {
    const d = decodeBytes(latin1(`<?xml version="1.0" encoding="ISO-8859-1"?><a>ñ</a>`), "text/xml");
    expect(d.text).toBe(`<?xml version="1.0" encoding="ISO-8859-1"?><a>ñ</a>`);
  });
  it("defaults to UTF-8", () => {
    const d = decodeBytes(new TextEncoder().encode("<a>ñ€</a>"), null);
    expect(d.text).toBe("<a>ñ€</a>");
    expect(d.charset).toBe("utf-8");
  });
  it("strips a UTF-8 BOM", () => {
    const body = new TextEncoder().encode("<a>x</a>");
    const withBom = new Uint8Array([0xef, 0xbb, 0xbf, ...body]);
    expect(decodeBytes(withBom, null).text).toBe("<a>x</a>");
  });
  it("ignores unknown charset names", () => {
    expect(charsetOfContentType("text/xml; charset=klingon-9")).toBeNull();
    expect(charsetOfContentType('text/xml; charset="utf-8"')).toBe("utf-8");
    expect(charsetOfContentType(null)).toBeNull();
    expect(decodeBytes(new TextEncoder().encode("<a>x</a>"), "text/xml; charset=klingon-9").text).toBe("<a>x</a>");
  });
  it("reads the declared encoding of a text", () => {
    expect(declaredCharset(`<?xml version="1.0" encoding="ISO-8859-1"?><a/>`)).toBe("iso-8859-1");
    expect(declaredCharset("<a/>")).toBe("utf-8");
  });
  it("encodes UTF-8 and ISO-8859-1, and warns about the rest", () => {
    expect([...encodeText("ñ", "utf-8").bytes]).toEqual([0xc3, 0xb1]);
    expect([...encodeText("ñ", "iso-8859-1").bytes]).toEqual([0xf1]);
    const lossy = encodeText("€", "iso-8859-1");
    expect([...lossy.bytes]).toEqual([0x3f]);
    expect(lossy.warnings).toHaveLength(1);
    expect(encodeText("a", "utf-16le").warnings[0]).toContain("UTF-8");
  });
  it("encodes base64", () => {
    expect(stringToBase64("alice:pä:ss")).toBe(Buffer.from("alice:pä:ss", "utf-8").toString("base64"));
    expect(bytesToBase64(new Uint8Array(100_000).fill(65))).toBe(Buffer.alloc(100_000, 65).toString("base64"));
  });
});
