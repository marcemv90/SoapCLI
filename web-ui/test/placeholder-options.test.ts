import { describe, expect, it } from "vitest";
import { PLACEHOLDER_NAME, duplicateValues, newOptionRow, parseOptionLines, toOptions } from "../src/lib/placeholder-options";

describe("pasting a list of options", () => {
  it("reads one option per line, with an optional label after a bar", () => {
    const rows = parseOptionLines("ES | Spain\nNL\r\n  DE|Germany  \n\n   \n | no value\nFR | ");
    expect(rows.map((r) => [r.value, r.label])).toEqual([["ES", "Spain"], ["NL", ""], ["DE", "Germany"], ["FR", ""]]);
    expect(new Set(rows.map((r) => r.id)).size).toBe(rows.length);
  });

  it("keeps a bar that belongs to the label", () => {
    expect(parseOptionLines("A | one | two").map((r) => [r.value, r.label])).toEqual([["A", "one | two"]]);
  });

  it("is nothing for nothing", () => {
    expect(parseOptionLines("")).toEqual([]);
    expect(parseOptionLines("\n \n")).toEqual([]);
  });
});

describe("the rows of the editor", () => {
  it("finds a value listed twice, ignoring empty rows", () => {
    const rows = [newOptionRow("a"), newOptionRow(""), newOptionRow("b"), newOptionRow("a"), newOptionRow("")];
    expect(duplicateValues(rows)).toEqual(["a"]);
    expect(duplicateValues([newOptionRow("a"), newOptionRow("b")])).toEqual([]);
  });

  it("sends only the rows with a value, and a label only when there is one", () => {
    expect(toOptions([newOptionRow("ES", " Spain "), newOptionRow("NL", "  "), newOptionRow("", "orphan label"), newOptionRow("DE")]))
      .toEqual([{ value: "ES", label: "Spain" }, { value: "NL" }, { value: "DE" }]);
  });

  it("only takes names a request's placeholder can have", () => {
    for (const ok of ["ENV", "order_id", "_x", "A.b-c", "A".repeat(64)]) expect(PLACEHOLDER_NAME.test(ok), ok).toBe(true);
    for (const bad of ["", "1A", "has space", "ñ", "A".repeat(65), "a:b"]) expect(PLACEHOLDER_NAME.test(bad), bad).toBe(false);
  });
});
