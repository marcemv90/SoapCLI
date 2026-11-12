import { describe, expect, it } from "vitest";
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
