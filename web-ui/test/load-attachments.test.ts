import { describe, expect, it } from "vitest";
import { loadAttachments, pendingItems, summarize, type LoadItem } from "../src/lib/load-attachments";

const files = [
  { cid: "a", path: "one.pdf" },
  { cid: "b", path: "two.pdf" },
  { cid: "c", path: "three.pdf" },
];

describe("loadAttachments", () => {
  it("loads the files one after the other and reports each step", async () => {
    const order: string[] = [];
    const seen: LoadItem[][] = [];
    const result = await loadAttachments(
      files,
      async (path) => {
        order.push(path);
        return { name: path, size: path.length * 1000 };
      },
      (items) => seen.push(items),
    );
    expect(result.ok).toBe(true);
    expect(order).toEqual(["one.pdf", "two.pdf", "three.pdf"]);
    expect(result.items.map((i) => i.state)).toEqual(["done", "done", "done"]);
    // starts all pending, then one file is loading at a time, and the progress only ever goes forward
    expect(seen[0].map((i) => i.state)).toEqual(["pending", "pending", "pending"]);
    expect(seen.every((items) => items.filter((i) => i.state === "loading").length <= 1)).toBe(true);
    const percents = seen.map((items) => summarize(items).percent);
    expect(percents).toEqual([...percents].sort((x, y) => x - y));
    expect(percents.at(-1)).toBe(100);
  });

  it("goes on after a failure so every problem is reported, and says it was not ok", async () => {
    const result = await loadAttachments(
      files,
      async (path) => {
        if (path === "two.pdf") throw new Error("The attachment file 'two.pdf' was not found");
        return { name: path, size: 10 };
      },
      () => {},
    );
    expect(result.ok).toBe(false);
    expect(result.items.map((i) => i.state)).toEqual(["done", "error", "done"]);
    expect(result.items[1].error).toContain("not found");
  });

  it("stops at once when cancelled", async () => {
    const ctl = new AbortController();
    const calls: string[] = [];
    const run = loadAttachments(
      files,
      async (path, signal) => {
        calls.push(path);
        ctl.abort();
        signal?.throwIfAborted();
        return { name: path, size: 1 };
      },
      () => {},
      ctl.signal,
    );
    await expect(run).rejects.toMatchObject({ name: "AbortError" });
    expect(calls).toEqual(["one.pdf"]);
  });

  it("has nothing to do without files", async () => {
    const result = await loadAttachments([], async () => ({ name: "x", size: 0 }), () => {});
    expect(result).toEqual({ ok: true, items: [] });
    expect(summarize([]).percent).toBe(100);
  });
});

describe("summarize", () => {
  it("counts files and bytes of what loaded, and the files that finished either way", () => {
    const items: LoadItem[] = [
      { cid: "a", path: "a", state: "done", size: 1500 },
      { cid: "b", path: "b", state: "error", error: "x" },
      { cid: "c", path: "c", state: "loading" },
      { cid: "d", path: "d", state: "pending" },
    ];
    expect(summarize(items)).toEqual({ total: 4, finished: 2, loaded: 1, failed: 1, bytes: 1500, percent: 50 });
    expect(summarize(pendingItems([{ cid: "a", path: "a" }])).percent).toBe(0);
  });
});
