import { describe, expect, it } from "vitest";
import {
  MASK, clone, emptyDefinition, getAt, isDirty, isNullAt, isSecretName, repeatedNames, rowsAt, setAt, setNumberOrText, setText, setTri,
  tableOf, textAt, triAt, unknownKeys, PROFILE_NAME, type Row,
} from "../src/lib/profile-form";

describe("reading and writing a setting by path", () => {
  const def = { endpoint: "http://a", keystore: { path: "k.p12", type: "PKCS12" }, timeout: 30, mtom: true, proxy: null, mystery: [1] };

  it("reads text, numbers, booleans and nothing", () => {
    expect(textAt(def, ["endpoint"])).toBe("http://a");
    expect(textAt(def, ["keystore", "path"])).toBe("k.p12");
    expect(textAt(def, ["timeout"])).toBe("30");
    expect(textAt(def, ["mtom"])).toBe("true");
    expect(textAt(def, ["nope"])).toBe("");
    expect(textAt(def, ["proxy", "host"])).toBe("");
    expect(getAt(def, ["keystore", "type"])).toBe("PKCS12");
    expect(isNullAt(def, ["proxy"])).toBe(true);
    expect(isNullAt(def, ["endpoint"])).toBe(false);
  });

  it("never changes what it was given", () => {
    const before = clone(def);
    setAt(def, ["keystore", "path"], "other");
    setText(def, ["endpoint"], "");
    expect(def).toEqual(before);
  });

  it("sets nested values and creates the group", () => {
    expect(setText({}, ["proxy", "host"], "px")).toEqual({ proxy: { host: "px" } });
    expect(setText(def, ["keystore", "path"], "new.p12").keystore).toEqual({ path: "new.p12", type: "PKCS12" });
  });

  it("an empty field is a setting that is not there, and an empty group goes with it", () => {
    expect(setText(def, ["endpoint"], "")).not.toHaveProperty("endpoint");
    let d = setText({ proxy: { host: "px" } }, ["proxy", "host"], "");
    expect(d).toEqual({});
    d = setText({ proxy: { host: "px", port: 1 } }, ["proxy", "host"], "");
    expect(d).toEqual({ proxy: { port: 1 } });
  });

  it("a group switched off with null comes back as an object when something is set in it", () => {
    expect(setText(def, ["proxy", "host"], "px").proxy).toEqual({ host: "px" });
    expect(setAt(def, ["proxy"], null).proxy).toBeNull();
    expect(setAt(def, ["proxy"], undefined)).not.toHaveProperty("proxy");
  });

  it("whole numbers are stored as numbers and ${VAR} stays text", () => {
    expect(setNumberOrText({}, ["timeout"], "45")).toEqual({ timeout: 45 });
    expect(setNumberOrText({}, ["timeout"], " 45 ")).toEqual({ timeout: 45 });
    expect(setNumberOrText({}, ["timeout"], "${TIMEOUT}")).toEqual({ timeout: "${TIMEOUT}" });
    expect(setNumberOrText({}, ["proxy", "port"], "3128")).toEqual({ proxy: { port: 3128 } });
    expect(setNumberOrText({ timeout: 5 }, ["timeout"], "")).toEqual({});
    expect(setNumberOrText({}, ["timeout"], "3.5")).toEqual({ timeout: "3.5" }); // not a whole number: the server will say so
  });

  it("keeps the settings it does not know", () => {
    expect(unknownKeys(def)).toEqual(["mystery"]);
    expect(setText(def, ["endpoint"], "http://b").mystery).toEqual([1]);
    expect(unknownKeys(emptyDefinition())).toEqual([]);
  });
});

describe("yes/no settings", () => {
  it("tell not set, yes, no, and something written as text", () => {
    expect(triAt({}, ["mtom"])).toEqual({ state: "unset" });
    expect(triAt({ mtom: true }, ["mtom"])).toEqual({ state: "true" });
    expect(triAt({ mtom: "FALSE" }, ["mtom"])).toEqual({ state: "false" });
    expect(triAt({ mtom: "${MTOM}" }, ["mtom"])).toEqual({ state: "other", raw: "${MTOM}" });
    expect(triAt({ mtom: null }, ["mtom"])).toEqual({ state: "unset" });
  });

  it("are written as real booleans, or removed", () => {
    expect(setTri({}, ["mtom"], "true")).toEqual({ mtom: true });
    expect(setTri({ mtom: true }, ["mtom"], "false")).toEqual({ mtom: false });
    expect(setTri({ mtom: true }, ["mtom"], "unset")).toEqual({});
  });
});

describe("name/value tables", () => {
  it("round trip, with removals of inherited entries kept", () => {
    const rows = rowsAt({ headers: { "X-A": "1", "X-Gone": null, "X-N": 5 } }, "headers");
    expect(rows.map((r) => [r.name, r.value, !!r.removed])).toEqual([["X-A", "1", false], ["X-Gone", "", true], ["X-N", "5", false]]);
    expect(tableOf(rows)).toEqual({ "X-A": "1", "X-Gone": null, "X-N": "5" });
    expect(new Set(rows.map((r) => r.id)).size).toBe(3);
  });

  it("drops rows without a name, keeps empty values (they mean something for variables), and is nothing when empty", () => {
    const rows: Row[] = [{ id: "1", name: " ", value: "x" }, { id: "2", name: "ORDER", value: "" }];
    expect(tableOf(rows)).toEqual({ ORDER: "" });
    expect(tableOf([{ id: "1", name: "", value: "x" }])).toBeUndefined();
    expect(tableOf([])).toBeUndefined();
    expect(rowsAt({}, "headers")).toEqual([]);
    expect(rowsAt({ headers: "oops" }, "headers")).toEqual([]);
  });

  it("finds a name used twice", () => {
    const rows: Row[] = [{ id: "1", name: "A", value: "1" }, { id: "2", name: "B", value: "" }, { id: "3", name: " A ", value: "2" }];
    expect(repeatedNames(rows)).toEqual(["A"]);
    expect(tableOf(rows)).toEqual({ A: "2", B: "" });
  });
});

describe("secrets", () => {
  it("use the server's idea of a secret name", () => {
    for (const name of ["authBasic", "authBearer", "password", "aliasPkPass", "X-Api-Key", "X_API_KEY", "Authorization", "Cookie", "secret", "MY_TOKEN", "credential"]) {
      expect(isSecretName(name), name).toBe(true);
    }
    for (const name of ["endpoint", "X-Client", "user", "path", "host", "ORDER_ID"]) expect(isSecretName(name), name).toBe(false);
    expect(MASK).toBe("****");
  });
});

describe("the rest", () => {
  it("knows whether the form changed", () => {
    const a = { endpoint: "x", headers: { A: "1" } };
    expect(isDirty(clone(a), a)).toBe(false);
    expect(isDirty(setText(a, ["endpoint"], "y"), a)).toBe(true);
  });

  it("only lets a new profile have a name that is easy to type on a command line", () => {
    for (const ok of ["acc", "prod-2", "a.b_c", "A".repeat(64)]) expect(PROFILE_NAME.test(ok), ok).toBe(true);
    for (const bad of ["", "has space", "slash/name", "ñ", "A".repeat(65), "quote'"]) expect(PROFILE_NAME.test(bad), bad).toBe(false);
  });
});
