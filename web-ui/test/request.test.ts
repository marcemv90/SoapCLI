import { describe, expect, it } from "vitest";
import { RequestError, buildRequest, isForbiddenHeader } from "../src/lib/request";
import { ENV11, ENV12, attachment, bodyAsLatin1, draft, header, headerOf } from "./helpers";

describe("buildRequest (same cases as RequestBuilderTest.java)", () => {
  it("sends SOAP 1.2 as application/soap+xml with no SOAPAction", () => {
    const r = buildRequest(draft());
    expect(r.soapVersion).toBe("1.2");
    expect(headerOf(r.headers, "Content-Type")).toBe("application/soap+xml; charset=utf-8");
    expect(headerOf(r.headers, "SOAPAction")).toBeUndefined();
    expect(r.warnings).toEqual([]);
  });

  it("detects SOAP 1.1: text/xml and an empty SOAPAction", () => {
    const r = buildRequest(draft({ body: ENV11 }));
    expect(r.soapVersion).toBe("1.1");
    expect(headerOf(r.headers, "Content-Type")).toBe("text/xml; charset=utf-8");
    expect(headerOf(r.headers, "SOAPAction")).toBe('""');
  });

  it("puts the action in the header for 1.1 and in the content type for 1.2", () => {
    expect(headerOf(buildRequest(draft({ body: ENV11, soapAction: "urn:doIt" })).headers, "SOAPAction")).toBe('"urn:doIt"');
    expect(headerOf(buildRequest(draft({ soapAction: "urn:doIt" })).headers, "Content-Type")).toBe(
      'application/soap+xml; charset=utf-8; action="urn:doIt"',
    );
    expect(headerOf(buildRequest(draft({ body: ENV11, soapAction: '"urn:quoted"' })).headers, "SOAPAction")).toBe('"urn:quoted"');
  });

  it("lets an explicit version beat detection", () => {
    expect(headerOf(buildRequest(draft({ soapVersion: "1.1" })).headers, "Content-Type")).toBe("text/xml; charset=utf-8");
  });

  it("warns and assumes 1.2 when the version cannot be detected", () => {
    const r = buildRequest(draft({ body: "<a/>" }));
    expect(r.soapVersion).toBe("1.2");
    expect(r.warnings).toHaveLength(1);
    expect(r.warnings[0]).toContain("SOAP 1.2");
  });

  it("lets user headers override the defaults, whatever the case", () => {
    const r = buildRequest(draft({ headers: [header("content-type", "text/xml"), header("X-Env", "acc")] }));
    expect(headerOf(r.headers, "Content-Type")).toBe("text/xml");
    expect(headerOf(r.headers, "X-Env")).toBe("acc");
  });

  it("ignores disabled and nameless headers", () => {
    const r = buildRequest(draft({ headers: [{ ...header("X-Off", "1"), enabled: false }, header("  ", "x")] }));
    expect(headerOf(r.headers, "X-Off")).toBeUndefined();
  });

  it("builds Basic and Bearer authorization", () => {
    const basic = buildRequest(draft({ auth: { kind: "basic", user: "alice", password: "pä:ss", token: "" } }));
    expect(headerOf(basic.headers, "Authorization")).toBe(`Basic ${Buffer.from("alice:pä:ss", "utf-8").toString("base64")}`);
    const bearer = buildRequest(draft({ auth: { kind: "bearer", user: "", password: "", token: " tok " } }));
    expect(headerOf(bearer.headers, "Authorization")).toBe("Bearer tok");
  });

  it("rejects what cannot work", () => {
    expect(() => buildRequest(draft({ endpoint: "" }))).toThrow(RequestError);
    expect(() => buildRequest(draft({ endpoint: "not a url" }))).toThrow(RequestError);
    expect(() => buildRequest(draft({ endpoint: "file:///etc/hostname" }))).toThrow(/http/);
    expect(() => buildRequest(draft({ body: "  \n" }))).toThrow(/empty/);
    expect(() => buildRequest(draft({ attachments: [attachment("bad cid", "x")] }))).toThrow(/CID/);
  });

  it("applies placeholders and reports values nothing uses", async () => {
    const r = buildRequest(draft({ body: ENV12.replace("<ping/>", "<ping>___ID:1___</ping>"), args: { ID: "99", IDD: "typo" } }));
    expect(await bodyAsLatin1(r.body)).toContain("<ping>99</ping>");
    expect(r.warnings).toHaveLength(1);
    expect(r.warnings[0]).toContain("IDD");
  });

  it("sends the request in the encoding it declares", async () => {
    const body = `<?xml version="1.0" encoding="ISO-8859-1"?>${ENV12.replace("<ping/>", "<n>ñ</n>")}`;
    const r = buildRequest(draft({ body }));
    expect(headerOf(r.headers, "Content-Type")).toBe("application/soap+xml; charset=iso-8859-1");
    const sent = r.body as Uint8Array;
    expect([...sent].filter((b) => b === 0xf1)).toHaveLength(1);
    expect(r.contentLength).toBe(sent.length);
  });

  it("inlines attachments in text only and hides the data in the preview", async () => {
    const xml = ENV12.replace("<ping/>", '<payload payloadId="cid:message"><value>cid:message</value></payload>');
    const r = buildRequest(draft({ body: xml, attachments: [attachment("message", "hello", "a.bin")] }));
    const sent = await bodyAsLatin1(r.body);
    expect(sent).toContain('payloadId="cid:message"');
    expect(sent).toContain(`<value>${Buffer.from("hello").toString("base64")}</value>`);
    expect(r.mtom).toBe(false);
    expect(r.previewXml).toContain("[base64 of a.bin, 5 bytes]");
    expect(r.previewXml).not.toContain("aGVsbG8");
  });
});

describe("MTOM", () => {
  it("builds a multipart with xop includes and exact content ids", async () => {
    const r = buildRequest(draft({
      body: ENV12.replace("<ping/>", "<x>cid:message1</x><y>cid:message10</y>"),
      mtom: true,
      attachments: [attachment("message1", "hello"), attachment("message10", "WORLD")],
    }));
    expect(r.mtom).toBe(true);
    expect(r.contentType).toMatch(/^multipart\/related; type="application\/xop\+xml"; start="<rootpart@soapcli>"/);
    expect(r.contentType).toContain('start-info="application/soap+xml"');
    expect(r.contentType).not.toContain("action");

    const body = await bodyAsLatin1(r.body);
    const boundary = /boundary="([^"]+)"/.exec(r.contentType)![1];
    expect(body.startsWith(`--${boundary}\r\n`)).toBe(true);
    expect(body.endsWith(`--${boundary}--\r\n`)).toBe(true);
    expect(body).toContain('<y><inc:Include href="cid:message10"');
    expect(body).not.toContain("/>0</y>"); // message10 must not become message1 + "0"
    expect(body).toContain("Content-ID: <message1>\r\nContent-Transfer-Encoding: binary\r\n\r\nhello\r\n");
    expect(body).toContain("Content-ID: <message10>\r\nContent-Transfer-Encoding: binary\r\n\r\nWORLD\r\n");
    expect(body).not.toContain("submitMessage");
    expect(r.contentLength).toBe(body.length);
  });

  it("takes the action from the SOAP action for 1.2 and omits it for 1.1", async () => {
    const xml12 = ENV12.replace("<ping/>", "<v>cid:m</v>");
    const r12 = buildRequest(draft({ body: xml12, mtom: true, soapAction: "submitMessage", attachments: [attachment("m", "x")] }));
    expect(r12.contentType).toContain('; action="submitMessage"');
    expect(await bodyAsLatin1(r12.body)).toContain('type="application/soap+xml"; action="submitMessage"');

    const r11 = buildRequest(draft({ body: ENV11.replace("<ping/>", "<v>cid:m</v>"), mtom: true, soapAction: "submitMessage", attachments: [attachment("m", "x")] }));
    expect(r11.contentType).toContain('start-info="text/xml"');
    expect(r11.contentType).not.toContain("action=");
    expect(headerOf(r11.headers, "SOAPAction")).toBe('"submitMessage"');
  });

  it("keeps binary data intact", async () => {
    const data = Uint8Array.from([0, 1, 2, 255, 10, 13, 10]);
    const r = buildRequest(draft({
      body: ENV12.replace("<ping/>", "<v>cid:bin1</v>"),
      mtom: true,
      attachments: [{ id: "x", cid: "bin1", filename: "b.bin", size: data.length, data }],
    }));
    const bytes = new Uint8Array(await (r.body as Blob).arrayBuffer());
    const text = new TextDecoder("latin1").decode(bytes);
    const start = text.indexOf("Content-ID: <bin1>");
    const dataStart = text.indexOf("\r\n\r\n", start) + 4;
    expect([...bytes.subarray(dataStart, dataStart + 7)]).toEqual([...data]);
  });
});

describe("warnings", () => {
  it("explains unreferenced attachments and attribute-only references", () => {
    expect(buildRequest(draft({ attachments: [attachment("ghost", "x")] })).warnings[0]).toContain("not referenced");

    const attrOnly = ENV12.replace("<ping/>", '<p href="cid:m"/>');
    expect(buildRequest(draft({ body: attrOnly, attachments: [attachment("m", "x")] })).warnings[0]).toContain("only appears in attributes");
    expect(buildRequest(draft({ body: attrOnly, mtom: true, attachments: [attachment("m", "x")] })).warnings).toEqual([]);
    expect(buildRequest(draft({ mtom: true })).warnings[0]).toContain("MTOM has no effect");
  });

  it("knows which headers a browser refuses to set", () => {
    for (const h of ["Host", "cookie", "User-Agent", "Proxy-Authorization", "Sec-Fetch-Mode", "Origin"]) {
      expect(isForbiddenHeader(h)).toBe(true);
    }
    for (const h of ["SOAPAction", "Authorization", "X-Correlation-ID", "Content-Type"]) {
      expect(isForbiddenHeader(h)).toBe(false);
    }
    expect(buildRequest(draft({ headers: [header("Host", "virtual"), header("X-Ok", "1")] })).droppedHeaders).toEqual(["Host"]);
  });
});
