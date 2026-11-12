import { describe, expect, it } from "vitest";
import { fromStored, toStored } from "../src/lib/storage";
import { attachment, draft, header } from "./helpers";

describe("stored drafts", () => {
  it("never keeps a password, a token or a file", () => {
    const stored = toStored(draft({
      auth: { kind: "basic", user: "u", password: "secret", token: "tok" },
      attachments: [attachment("a", "x")],
    }));
    expect(JSON.stringify(stored)).not.toMatch(/secret|tok"|attachments/);
    expect(stored.auth).toEqual({ kind: "basic", user: "u" });
  });

  it("round-trips what the UI keeps", () => {
    const d = draft({ soapAction: "urn:a", args: { ID: "7" }, headers: [header("X-A", "1")], mtom: true, profile: "acc" });
    const back = fromStored(toStored(d));
    expect(back).toMatchObject({ endpoint: d.endpoint, body: d.body, soapAction: "urn:a", args: { ID: "7" }, mtom: true, profile: "acc" });
    expect(back.headers).toHaveLength(1);
    expect(back.attachments).toEqual([]);
  });

  it("drops what older versions stored (the browser/server choice)", () => {
    const back = fromStored({ endpoint: "http://x", body: "<a/>", transport: "browser" } as never);
    expect(back).not.toHaveProperty("transport");
    expect(back.endpoint).toBe("http://x");
    expect(back.profile).toBe("");
  });
});
