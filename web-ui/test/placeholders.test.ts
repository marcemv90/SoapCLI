import { describe, expect, it } from "vitest";
import { findPlaceholders } from "../src/lib/placeholders";

describe("findPlaceholders", () => {
  it("lists each placeholder once, in order", () => {
    expect(findPlaceholders("___A:1___ ___B:two___ ___A:other___")).toEqual([
      { name: "A", defaultValue: "1" },
      { name: "B", defaultValue: "two" },
    ]);
  });

  it("finds them in element names, attributes and CDATA, and keeps multiline defaults", () => {
    const xml = '<___TAG:order___ id="___ID:1___"><![CDATA[c=___ID:1___]]><n>___N:l1\nl2___</n></___TAG:order___>';
    expect(findPlaceholders(xml)).toEqual([
      { name: "TAG", defaultValue: "order" },
      { name: "ID", defaultValue: "1" },
      { name: "N", defaultValue: "l1\nl2" },
    ]);
  });

  it("does not let a stray triple underscore swallow the document", () => {
    expect(findPlaceholders("<a><note>use ___ as separator</note><v>___V:1___</v></a>")).toEqual([{ name: "V", defaultValue: "1" }]);
  });

  it("can be called repeatedly (no shared regex state)", () => {
    for (let i = 0; i < 3; i++) expect(findPlaceholders("___A:1___")).toHaveLength(1);
  });
});
