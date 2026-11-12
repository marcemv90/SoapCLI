import { describe, expect, it } from "vitest";
import { activeAttachments, pathAttachments, referencedCids } from "../src/lib/attachments";
import { findCids } from "../src/lib/cid";
import { attachment, draft, pathAttachment } from "./helpers";

describe("findCids", () => {
  it("shows the cid of an element, not the one of an attribute", () => {
    const xml = `<payload payloadId="cid:message" contentType="text/xml">
  <value>cid:1234</value>
</payload>`;
    expect(findCids(xml)).toEqual(["1234"]);
  });

  it("lists each cid once, in order of appearance", () => {
    expect(findCids("<a><x>cid:b</x><y>cid:a</y><z>cid:b</z></a>")).toEqual(["b", "a"]);
  });

  it("ignores comments, processing instructions and attributes with either quote", () => {
    expect(findCids(`<a href='cid:q' id="cid:r"><!-- cid:c --><?pi cid:d?><v>cid:e</v></a>`)).toEqual(["e"]);
  });

  it("reads CDATA only when asked (inline Base64 replaces it, MTOM does not)", () => {
    const xml = "<v><![CDATA[cid:a]]></v>";
    expect(findCids(xml)).toEqual([]);
    expect(findCids(xml, { cdata: true })).toEqual(["a"]);
  });

  it("takes the whole content-id and not a word that merely ends in cid:", () => {
    expect(findCids("<a>cid:part.1@host-x_y+z%20</a>")).toEqual(["part.1@host-x_y+z%20"]);
    expect(findCids("<a>mycid:nope</a>")).toEqual([]);
  });

  it("finds a reference among other text and several in one element", () => {
    expect(findCids("<a>file cid:one and cid:two!</a>")).toEqual(["one", "two"]);
  });

  it("is empty for text without references and for empty input", () => {
    expect(findCids("<a>cid: </a>")).toEqual([]);
    expect(findCids("")).toEqual([]);
  });

  it("can be called repeatedly (no shared regex state)", () => {
    for (let i = 0; i < 3; i++) expect(findCids("<a>cid:x</a>")).toEqual(["x"]);
  });
});

describe("activeAttachments", () => {
  const body = "<a><v>cid:keep</v><w href=\"cid:attr\"/></a>";

  it("keeps the files whose cid the body refers to, and drops the rest", () => {
    const d = draft({ body, attachments: [attachment("keep", "1"), attachment("gone", "2"), attachment("attr", "3")] });
    expect(activeAttachments(d).map((a) => a.cid)).toEqual(["keep"]);
  });

  it("follows what is typed: the file returns when its reference does", () => {
    const files = [attachment("keep", "1")];
    expect(activeAttachments(draft({ body: "<a/>", attachments: files }))).toEqual([]);
    expect(activeAttachments(draft({ body, attachments: files }))).toHaveLength(1);
  });

  it("lists the files named by path, which the server has to load, apart from the ones chosen here", () => {
    const d = draft({
      body: "<a><x>cid:p</x><y>cid:f</y><z>cid:blank</z><w>cid:gone</w></a>".replace("<w>cid:gone</w>", ""),
      attachments: [pathAttachment("p", "one.pdf"), attachment("f", "1"), pathAttachment("blank", " "), pathAttachment("unused", "x.pdf")],
    });
    expect(activeAttachments(d).map((a) => a.cid)).toEqual(["p", "f"]);
    expect(pathAttachments(d).map((a) => a.cid)).toEqual(["p"]);
  });

  it("counts CDATA references in inline mode only", () => {
    const cdata = "<v><![CDATA[cid:a]]></v>";
    expect(referencedCids({ body: cdata, mtom: false })).toEqual(["a"]);
    expect(referencedCids({ body: cdata, mtom: true })).toEqual([]);
  });
});
