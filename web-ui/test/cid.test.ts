import { describe, expect, it } from "vitest";
import { isValidCid, mentionsCid, replaceCids, xopInclude } from "../src/lib/cid";

describe("replaceCids (same cases as CidReplacerTest.java)", () => {
  it("matches exact content ids, not prefixes", () => {
    const xml = "<p><x>cid:message1</x><y>cid:message10</y><z>cid:message1-b</z></p>";
    const r = replaceCids(xml, ["message1", "message10"], (c) => `[${c}]`, false);
    expect(r.text).toBe("<p><x>[message1]</x><y>[message10]</y><z>cid:message1-b</z></p>");
    expect([...r.referenced].sort()).toEqual(["message1", "message10"]);
  });

  it("never touches attributes or comments", () => {
    const xml = '<p payloadId="cid:a"><!-- cid:a --><v>cid:a</v></p>';
    expect(replaceCids(xml, ["a"], () => "X", true).text).toBe('<p payloadId="cid:a"><!-- cid:a --><v>X</v></p>');
  });

  it("searches CDATA only when asked", () => {
    const xml = "<v><![CDATA[cid:a]]></v>";
    expect(replaceCids(xml, ["a"], () => "X", false).text).toBe(xml);
    expect(replaceCids(xml, ["a"], () => "X", true).text).toBe("<v><![CDATA[X]]></v>");
  });

  it("computes each replacement once", () => {
    let calls = 0;
    replaceCids("<a>cid:x</a><b>cid:x</b>", ["x"], () => {
      calls++;
      return "Z";
    }, true);
    expect(calls).toBe(1);
  });

  it("does not rescan inserted text", () => {
    const r = replaceCids("<a>cid:m</a>", ["m", "x"], xopInclude, false);
    expect(r.text).toBe('<a><inc:Include href="cid:m" xmlns:inc="http://www.w3.org/2004/08/xop/include"/></a>');
  });

  it("quotes regex characters in content ids", () => {
    expect(replaceCids("<a>cid:part.1@host</a>", ["part.1@host"], () => "OK", false).text).toBe("<a>OK</a>");
    expect(replaceCids("<a>cid:partX1@host</a>", ["part.1@host"], () => "OK", false).text).toBe("<a>cid:partX1@host</a>");
  });

  it("finds attribute-only references and validates ids", () => {
    expect(mentionsCid('<p href="cid:a"/>', "a")).toBe(true);
    expect(mentionsCid('<p href="cid:ab"/>', "a")).toBe(false);
    expect(isValidCid("message-1.a@b")).toBe(true);
    expect(isValidCid("bad id")).toBe(false);
    expect(isValidCid("")).toBe(false);
  });
});
