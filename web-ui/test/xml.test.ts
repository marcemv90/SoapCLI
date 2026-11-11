import { describe, expect, it } from "vitest";
import { clean, escapeXml, isBlank, isEndTag, isSelfClosing, prettyPrint, scan, tagName } from "../src/lib/xml";

const rebuild = (xml: string) => scan(xml).map((t) => t.text).join("");

describe("scan", () => {
  it("covers the whole input exactly", () => {
    const xml = `<?xml version="1.0"?>\n<!DOCTYPE a [<!ENTITY x "y>z">]>\n<a b="1>2" c='3'>text<!-- c > d --><![CDATA[ <not-a-tag> ]]><b/></a>\n`;
    expect(rebuild(xml)).toBe(xml);
  });

  it("does not end a tag at a > inside an attribute value", () => {
    const t = scan(`<a href="x>y">t</a>`);
    expect(t[0].type).toBe("tag");
    expect(t[0].text).toBe(`<a href="x>y">`);
    expect(tagName(t[0])).toBe("a");
  });

  it("classifies tokens", () => {
    const t = scan("<a><!--c--><![CDATA[d]]><?pi x?>e</a><b/>");
    expect(t.map((x) => x.type)).toEqual(["tag", "comment", "cdata", "pi", "text", "tag", "tag"]);
    expect(isEndTag(t[5])).toBe(true);
    expect(isSelfClosing(t[6])).toBe(true);
    expect(tagName(t[6])).toBe("b");
  });

  it("knows qualified names and blank text", () => {
    expect(tagName(scan(`<soapenv:Envelope xmlns:soapenv="u">`)[0])).toBe("soapenv:Envelope");
    expect(tagName(scan("</soapenv:Envelope >")[0])).toBe("soapenv:Envelope");
    expect(isBlank(scan(" \n\t")[0])).toBe(true);
    expect(isBlank(scan(" x ")[0])).toBe(false);
  });

  it("turns unterminated constructs into text", () => {
    expect(scan("<a")[0].type).toBe("text");
    expect(scan("<!-- never closed")[0].type).toBe("text");
    expect(rebuild("<a")).toBe("<a");
  });
});

describe("prettyPrint (same cases as XmlFormatTest.java)", () => {
  it("indents nested elements and keeps text inline", () => {
    expect(prettyPrint("<a><b>1</b><c><d/></c></a>")).toBe("<a>\n  <b>1</b>\n  <c>\n    <d/>\n  </c>\n</a>\n");
  });

  it("is stable on already formatted input", () => {
    const pretty = "<a>\n  <b>1</b>\n  <c>\n    <d/>\n  </c>\n</a>\n";
    expect(prettyPrint(pretty)).toBe(pretty);
  });

  it("keeps declaration, comments, quoting and namespaces as written", () => {
    const input = `<?xml version="1.0" encoding="UTF-8"?><s:E xmlns:s='u'><!-- hi --><s:B a="1"  b='2'>x</s:B></s:E>`;
    expect(prettyPrint(input)).toBe(
      `<?xml version="1.0" encoding="UTF-8"?>\n<s:E xmlns:s='u'>\n  <!-- hi -->\n  <s:B a="1"  b='2'>x</s:B>\n</s:E>\n`,
    );
  });

  it("does not rewrite entities or CDATA", () => {
    expect(prettyPrint("<a><b>x &amp; y</b><c><![CDATA[ <raw> & ]]></c></a>")).toBe(
      "<a>\n  <b>x &amp; y</b>\n  <c><![CDATA[ <raw> & ]]></c>\n</a>\n",
    );
  });

  it("leaves mixed content untouched", () => {
    expect(prettyPrint("<p>hello <b>big</b> world</p>")).toBe("<p>hello <b>big</b> world</p>\n");
  });

  it("treats text with surrounding whitespace as data", () => {
    expect(prettyPrint("<a>\n\n  <b>  padded  </b>\n\n</a>")).toBe("<a>\n  <b>  padded  </b>\n</a>\n");
  });

  it("drops empty lines between elements", () => {
    expect(prettyPrint("<a>\r\n\r\n   <b/>\r\n\r\n   <c/>\r\n\r\n</a>")).toBe("<a>\n  <b/>\n  <c/>\n</a>\n");
  });

  it("returns malformed input unchanged", () => {
    for (const bad of ["<a><b></a>", "<html><body><br></body></html>", "plain text"]) {
      expect(prettyPrint(bad)).toBe(bad);
    }
  });

  it("only echoes a DOCTYPE with an external entity, never resolves it", () => {
    const xml = `<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/hostname">]><a>&xxe;</a>`;
    expect(prettyPrint(xml)).toBe(
      `<?xml version="1.0"?>\n<!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/hostname">]>\n<a>&xxe;</a>\n`,
    );
  });

  it("handles empty input", () => {
    expect(prettyPrint("")).toBe("");
    expect(prettyPrint("  ")).toBe("  ");
  });
});

describe("clean and escape", () => {
  it("removes a leading BOM and illegal control characters only", () => {
    expect(clean("﻿<a>x​y</a>")).toBe("<a>x​y</a>");
    expect(clean("<a>x\u0001y</a>")).toBe("<a>xy</a>");
    expect(clean("<a>\t\r\n</a>")).toBe("<a>\t\r\n</a>");
    expect(clean("a﻿b")).toBe("a﻿b");
  });

  it("escapes the five entities", () => {
    expect(escapeXml(`Tom & Jerry <3 "q" 's' >`)).toBe("Tom &amp; Jerry &lt;3 &quot;q&quot; &apos;s&apos; &gt;");
  });
});
