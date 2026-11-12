import { describe, expect, it } from "vitest";
import { buildHash, parseRoute } from "../src/lib/route";

describe("the address of a page", () => {
  it("reads the page, the workspace and the saved request a link names", () => {
    expect(parseRoute("#/library?w=billing")).toEqual({ page: "library", workspace: "billing", open: null });
    expect(parseRoute("#/request?w=billing&open=get-order-k3f9a")).toEqual({ page: "request", workspace: "billing", open: "get-order-k3f9a" });
    expect(parseRoute("#/workspaces")).toEqual({ page: "workspaces", workspace: null, open: null });
  });

  it("falls back to the request page for anything it does not know", () => {
    for (const hash of ["", "#", "#/", "#/nothing", "#/library/../x", "#profiles"]) {
      expect(parseRoute(hash).page, hash).toBe(hash === "#profiles" ? "profiles" : "request");
    }
  });

  it("ignores a workspace name that could not be a folder of the server", () => {
    for (const w of ["", "../x", "a/b", ".hidden", "-lead", "has space", "x".repeat(65), "<script>"]) {
      expect(parseRoute(`#/request?w=${encodeURIComponent(w)}`).workspace, w).toBeNull();
    }
    expect(parseRoute("#/request?w=my.team_1-a").workspace).toBe("my.team_1-a");
  });

  it("ignores an empty or absurd id to open", () => {
    expect(parseRoute("#/request?open=").open).toBeNull();
    expect(parseRoute(`#/request?open=${"x".repeat(121)}`).open).toBeNull();
  });

  it("builds an address that reads back the same", () => {
    expect(buildHash({ page: "request" })).toBe("#/request");
    expect(buildHash({ page: "profiles", workspace: null })).toBe("#/profiles");
    expect(buildHash({ page: "library", workspace: "billing" })).toBe("#/library?w=billing");
    const hash = buildHash({ page: "request", workspace: "billing", open: "a b&c=d" });
    expect(hash).toBe("#/request?w=billing&open=a+b%26c%3Dd");
    expect(parseRoute(hash)).toEqual({ page: "request", workspace: "billing", open: "a b&c=d" });
  });
});
