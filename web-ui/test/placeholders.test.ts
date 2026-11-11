import { describe, expect, it } from "vitest";
import { applyPlaceholders, findPlaceholders } from "../src/lib/placeholders";

describe("placeholders (same cases as PlaceholdersTest.java)", () => {
  it("uses defaults when no value is given", () => {
    const r = applyPlaceholders("<a>___ID:42___</a>", {});
    expect(r.text).toBe("<a>42</a>");
    expect(r.used.size).toBe(0);
  });

  it("overrides and reports which values were used", () => {
    const r = applyPlaceholders("<a>___ID:42___</a>", { ID: "7", TYPO: "x" });
    expect(r.text).toBe("<a>7</a>");
    expect([...r.used]).toEqual(["ID"]);
  });

  it("allows an override that is the empty string", () => {
    expect(applyPlaceholders("<a>___ID:42___</a>", { ID: "" }).text).toBe("<a></a>");
  });

  it("works in element names, attributes and CDATA", () => {
    const xml = "<___TAG:order___ id=\"___ID:1___\"><![CDATA[c=___ID:1___]]></___TAG:order___>";
    expect(applyPlaceholders(xml, { TAG: "invoice" }).text).toBe('<invoice id="1"><![CDATA[c=1]]></invoice>');
  });

  it("does not let a stray triple underscore swallow the document", () => {
    const xml = "<a><note>use ___ as separator, see http://x</note><v>___V:1___</v></a>";
    expect(applyPlaceholders(xml, {}).text).toBe("<a><note>use ___ as separator, see http://x</note><v>1</v></a>");
  });

  it("inserts values literally", () => {
    expect(applyPlaceholders("<a>___N:x___</a>", { N: "$1 \\ & <b> $& $`" }).text).toBe("<a>$1 \\ & <b> $& $`</a>");
  });

  it("supports multiline defaults and ignores prototype keys", () => {
    expect(applyPlaceholders("<a>___N:l1\nl2___</a>", {}).text).toBe("<a>l1\nl2</a>");
    expect(applyPlaceholders("<a>___constructor:d___</a>", {}).text).toBe("<a>d</a>");
  });

  it("lists each placeholder once, in order", () => {
    expect(findPlaceholders("___A:1___ ___B:two___ ___A:other___")).toEqual([
      { name: "A", defaultValue: "1" },
      { name: "B", defaultValue: "two" },
    ]);
  });

  it("can be called repeatedly (no shared regex state)", () => {
    for (let i = 0; i < 3; i++) expect(findPlaceholders("___A:1___")).toHaveLength(1);
  });
});
