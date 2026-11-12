import { describe, expect, it } from "vitest";
import { fromStored, toStored } from "../src/lib/storage";
import { attachment, draft, header, pathAttachment } from "./helpers";

describe("stored drafts", () => {
  it("never keeps a password, a token or the contents of a file", () => {
    const stored = toStored(draft({
      auth: { kind: "basic", user: "u", password: "secret", token: "tok" },
      attachments: [attachment("a", "FILE-BYTES", "report.pdf")],
    }));
    const json = JSON.stringify(stored);
    expect(json).not.toMatch(/secret|tok"|FILE-BYTES|report\.pdf|"data"/);
    expect(stored.auth).toEqual({ kind: "basic", user: "u" });
    expect(stored.attachments).toEqual([]);
  });

  it("keeps only the path of an attachment that is named by path", () => {
    const stored = toStored(draft({
      attachments: [pathAttachment("a", "invoices/one.pdf"), attachment("b", "bytes"), pathAttachment("c", "  ")],
    }));
    expect(stored.attachments).toEqual([{ cid: "a", path: "invoices/one.pdf" }]);
    const back = fromStored(JSON.parse(JSON.stringify(stored)));
    expect(back.attachments).toHaveLength(1);
    expect(back.attachments[0]).toMatchObject({ kind: "path", cid: "a", path: "invoices/one.pdf" });
    expect(back.attachments[0].id).toBeTruthy();
  });

  it("reads drafts stored before attachments had paths, and ignores nonsense", () => {
    expect(fromStored({ endpoint: "http://x", body: "<a/>" } as never).attachments).toEqual([]);
    const odd = fromStored({ body: "<a/>", attachments: [null, { cid: 1 }, { cid: "ok", path: "p" }, { cid: "no-path" }] } as never);
    expect(odd.attachments.map((a) => a.cid)).toEqual(["ok"]);
    expect(fromStored({ body: "<a/>", attachments: "x" } as never).attachments).toEqual([]);
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
