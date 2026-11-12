import { describe, expect, it, vi } from "vitest";
import {
  ServerError, checkAttachment, createWorkspace, deleteSavedRequest, fetchLibrary, fetchProfileRequest, fetchSavedRequest, fetchServerInfo,
  fetchServerProfiles, fetchWorkspaceInfo, fetchWorkspaces, formatViaServer, removeWorkspace, saveSavedRequest, sendViaServer, setWorkspace,
  toServerRequest,
} from "../src/lib/transports/server";
import { shellQuote, toCliCommand, toCurl } from "../src/lib/command";
import { ENV12, attachment, draft, header, pathAttachment } from "./helpers";

describe("server transport", () => {
  it("maps a draft to the API request without leaking disabled headers", () => {
    const d = draft({
      body: ENV12.replace("<ping/>", "<v>cid:m</v>"),
      endpoint: " http://x/soap ",
      headers: [header("X-A", "1"), { ...header("X-Off", "2"), enabled: false }],
      args: { ID: "7" },
      auth: { kind: "bearer", user: "", password: "", token: "tok" },
      attachments: [attachment("m", "hi", "m.txt")],
      mtom: true,
      options: { timeoutSeconds: 30, followRedirects: true, insecure: true },
      profile: "acc",
    });
    const r = toServerRequest(d) as Record<string, unknown>;
    expect(r.endpoint).toBe("http://x/soap");
    expect(r.profile).toBe("acc");
    expect(r.headers).toEqual({ "X-A": "1" });
    expect(r.authBearer).toBe("tok");
    expect(r.authBasic).toBeUndefined();
    expect(r.insecure).toBe(true);
    expect(r.attachments).toEqual([{ cid: "m", filename: "m.txt", contentBase64: Buffer.from("hi").toString("base64") }]);
  });

  it("sends a file named by path as its path, and one chosen here as Base64", () => {
    const d = draft({
      body: ENV12.replace("<ping/>", "<a>cid:server</a><b>cid:local</b>"),
      attachments: [pathAttachment("server", "  invoices/one.pdf "), attachment("local", "hi", "hi.txt")],
    });
    expect(toServerRequest(d).attachments).toEqual([
      { cid: "server", path: "invoices/one.pdf" },
      { cid: "local", filename: "hi.txt", contentBase64: Buffer.from("hi").toString("base64") },
    ]);
  });

  it("does not send a path that is blank", () => {
    const d = draft({ body: ENV12.replace("<ping/>", "<a>cid:x</a>"), attachments: [pathAttachment("x", "   ")] });
    expect(toServerRequest(d).attachments).toEqual([]);
  });

  it("sends only the files the body refers to with cid:", () => {
    const d = draft({
      body: ENV12.replace("<ping/>", '<v>cid:used</v><w ref="cid:attribute"/>'),
      attachments: [attachment("used", "1"), attachment("attribute", "2"), attachment("deleted", "3")],
    });
    expect((toServerRequest(d).attachments as { cid: string }[]).map((a) => a.cid)).toEqual(["used"]);
  });

  it("turns the API answer into a response view", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      status: 500, statusMessage: "Internal Server Error", elapsedMillis: 12, uri: "http://t/soap", contentType: "text/xml",
      bodySize: 5, binary: false, body: "<f/>\n", headers: [{ name: "Content-Type", value: "text/xml" }],
      request: { endpoint: "http://t/soap", soapVersion: "1.1", headers: [{ name: "Authorization", value: "Basic ****" }],
        contentLength: 9, mtom: false, previewXml: "<x/>", attachments: [], warnings: ["w"] },
    }), { status: 200 })));
    const view = await sendViaServer(draft());
    expect(view.status).toBe(500);
    expect(view.elapsedMs).toBe(12);
    expect(view.request.headers[0]).toEqual(["Authorization", "Basic ****"]);
    expect(view.request.warnings).toEqual(["w"]);
    vi.unstubAllGlobals();
  });

  it("turns API errors and an absent server into typed errors", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: { category: "forbidden", message: "nope" } }), { status: 403 })));
    await expect(sendViaServer(draft())).rejects.toMatchObject({ category: "forbidden", message: "nope", status: 403 });

    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("fetch failed"); }));
    await expect(sendViaServer(draft())).rejects.toBeInstanceOf(ServerError);
    expect(await fetchServerInfo()).toBeNull(); // a static copy of the UI has no API
    vi.unstubAllGlobals();
  });
});

describe("equivalent commands", () => {
  const d = draft({
    body: ENV12.replace("<ping/>", "<v>cid:m</v>"),
    endpoint: "https://x/soap",
    soapVersion: "1.1",
    soapAction: "urn:it's",
    headers: [header("X-A", "1")],
    args: { ID: "7" },
    auth: { kind: "basic", user: "u", password: "p", token: "" },
    attachments: [attachment("m", "hi", "m.txt")],
    mtom: true,
    options: { timeoutSeconds: 30, followRedirects: true, insecure: true },
  });

  it("quotes for a shell", () => {
    expect(shellQuote("it's")).toBe(`'it'\\''s'`);
  });

  it("writes the java command without any secret", () => {
    const cmd = toCliCommand(d, "jar");
    expect(cmd).toContain("java -jar SoapCLI.jar");
    expect(cmd).toContain("--endpoint 'https://x/soap'");
    expect(cmd).toContain("--soap-action 'urn:it'\\''s'");
    expect(cmd).toContain("--header 'X-A: 1'");
    expect(cmd).toContain("--arg 'ID=7'");
    expect(cmd).toContain("--auth-basic-env SOAP_CREDENTIALS");
    expect(cmd).toContain("--attachment 'm=./m.txt'");
    for (const flag of ["--mtom", "--timeout 30", "--follow-redirects", "--insecure", "--soap-version 1.1"]) expect(cmd).toContain(flag);
    expect(cmd).not.toContain(":p'");
  });

  it("writes the docker variant", () => {
    const cmd = toCliCommand(d, "docker");
    expect(cmd).toContain('docker run --rm -v "$PWD:/work" -e SOAP_CREDENTIALS ghcr.io/marcemv90/soapcli');
  });

  it("writes a path as it is on the server, and a local file as sitting next to request.xml", () => {
    const cmd = toCliCommand(draft({
      body: ENV12.replace("<ping/>", "<a>cid:srv</a><b>cid:loc</b>"),
      attachments: [pathAttachment("srv", "/work/in voices/one.pdf"), attachment("loc", "x", "two.txt")],
    }), "jar");
    expect(cmd).toContain("--attachment 'srv=/work/in voices/one.pdf'");
    expect(cmd).toContain("--attachment 'loc=./two.txt'");
  });

  it("leaves out an attachment the body no longer refers to", () => {
    expect(toCliCommand({ ...d, body: ENV12 }, "jar")).not.toContain("--attachment");
  });

  // What the server answers to /api/preview: credentials masked.
  const view = {
    url: "https://x/soap",
    mtom: false,
    headers: [
      ["Content-Type", 'application/soap+xml; charset=utf-8; action="urn:a"'],
      ["Accept", "text/xml"],
      ["Authorization", "Basic ****"],
      ["X-Api-Key", "****"],
      ["X-From-Profile", "****"],
    ] as [string, string][],
  };

  it("writes curl from the request the server built, with credentials as environment variables", () => {
    const plain = draft({ auth: { kind: "basic", user: "u", password: "p", token: "" }, headers: [header("X-Api-Key", "k-123")] });
    const curl = toCurl(plain, view)!;
    expect(curl).toContain("curl -sS -X POST 'https://x/soap'");
    expect(curl).toContain("-H 'Content-Type: application/soap+xml; charset=utf-8; action=\"urn:a\"'");
    expect(curl).toContain('-u "$SOAP_CREDENTIALS"');
    expect(curl).toContain("--data-binary @request.xml");
    expect(curl).not.toContain("Basic ****");
    expect(curl).not.toContain(":p'");
  });

  it("takes the headers typed in the form from the form, and flags what only a profile knows", () => {
    const curl = toCurl(draft({ headers: [header("X-Api-Key", "k-123")] }), view)!;
    expect(curl).toContain("-H 'X-Api-Key: k-123'");
    expect(curl).toContain("-H 'X-From-Profile: ****'");
    expect(curl).toContain("-H 'Authorization: Basic ****'"); // from a profile: nothing in the form replaces it
    expect(curl.split("\n")[0]).toContain("come from the server profile");
  });

  it("writes no curl for MTOM, which curl cannot rebuild", () => {
    expect(toCurl(d, { ...view, mtom: true })).toBeNull();
  });
});

describe("calls made for a workspace", () => {
  /** Records the calls and answers each with something every one of them can read (or the text it is told to). */
  function recorder(answer = '{"headers":[],"request":{"headers":[],"attachments":[],"warnings":[]}}') {
    const calls: { url: string; method: string; body?: string }[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url, method: init?.method ?? "GET", body: init?.body as string | undefined });
      return new Response(answer, { status: 200 });
    }));
    return calls;
  }
  const done = () => {
    setWorkspace(null);
    vi.unstubAllGlobals();
  };

  it("sends everything that belongs to a workspace to the workspace, and the rest to the server", async () => {
    const calls = recorder();
    setWorkspace("billing");
    await fetchServerProfiles();
    await fetchWorkspaceInfo();
    await fetchLibrary();
    await checkAttachment("doc.txt");
    await sendViaServer(draft());
    await fetchServerInfo();
    await fetchWorkspaces();
    await formatViaServer("<a/>");
    expect(calls.map((c) => c.url)).toEqual([
      "api/workspaces/billing/profiles",
      "api/workspaces/billing/info",
      "api/workspaces/billing/requests",
      "api/workspaces/billing/attachments/check",
      "api/workspaces/billing/send",
      "api/info",
      "api/workspaces",
      "api/format",
    ]);
    done();
  });

  it("calls the same addresses as before without a workspace, so a server that has none works as it always did", async () => {
    const calls = recorder();
    setWorkspace(null);
    await fetchServerProfiles();
    await fetchServerInfo();
    await checkAttachment("doc.txt");
    expect(calls.map((c) => c.url)).toEqual(["api/profiles", "api/info", "api/attachments/check"]);
    done();
  });

  it("changes the workspace the calls are made for as soon as another is opened", async () => {
    const calls = recorder();
    setWorkspace("billing");
    await fetchServerProfiles();
    setWorkspace("shipping");
    await fetchServerProfiles();
    expect(calls.map((c) => c.url)).toEqual(["api/workspaces/billing/profiles", "api/workspaces/shipping/profiles"]);
    done();
  });

  it("encodes what it puts in an address", async () => {
    const calls = recorder();
    setWorkspace("my.team_1");
    await fetchProfileRequest("acc/ept 1");
    await fetchSavedRequest("a/b c");
    expect(calls.map((c) => c.url)).toEqual([
      "api/workspaces/my.team_1/profiles/acc%2Fept%201/request",
      "api/workspaces/my.team_1/requests/a%2Fb%20c",
    ]);
    done();
  });

  it("creates and removes workspaces, and saves and deletes requests, with JSON posts", async () => {
    const calls = recorder('{"movedTo":".removed-old-1"}');
    setWorkspace("billing");
    await createWorkspace({ name: "new", description: "d" });
    expect(await removeWorkspace("old")).toBe(".removed-old-1");
    await saveSavedRequest({ name: "n", folder: "f", draft: { body: "<a/>" } as never });
    await saveSavedRequest({ id: "n-1", revision: "r1", name: "n", folder: "", draft: { body: "<a/>" } as never });
    await deleteSavedRequest("n-1", "r2");
    expect(calls.map((c) => [c.method, c.url])).toEqual([
      ["POST", "api/workspaces/create"],
      ["POST", "api/workspaces/remove"],
      ["POST", "api/workspaces/billing/requests/save"],
      ["POST", "api/workspaces/billing/requests/save"],
      ["POST", "api/workspaces/billing/requests/delete"],
    ]);
    expect(JSON.parse(calls[0].body!)).toEqual({ name: "new", description: "d" });
    expect(JSON.parse(calls[1].body!)).toEqual({ name: "old" });
    expect(JSON.parse(calls[2].body!)).toEqual({ name: "n", folder: "f", draft: { body: "<a/>" } });
    expect(JSON.parse(calls[3].body!)).toMatchObject({ id: "n-1", revision: "r1" });
    expect(JSON.parse(calls[4].body!)).toEqual({ id: "n-1", revision: "r2" });
    done();
  });

  it("reports what the server said when a save is refused because someone else changed the request", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ error: { category: "conflict", message: "changed since you opened it" } }), { status: 409 })));
    setWorkspace("billing");
    await expect(saveSavedRequest({ id: "x", revision: "old", name: "n", folder: "", draft: { body: "<a/>" } as never }))
      .rejects.toMatchObject({ category: "conflict", status: 409, message: "changed since you opened it" });
    done();
  });
});

describe("equivalent commands in a workspace", () => {
  const d = draft({
    profile: "acc",
    body: ENV12.replace("<ping/>", "<a>cid:doc</a><b>cid:abs</b>"),
    attachments: [pathAttachment("doc", "invoices/one.pdf"), pathAttachment("abs", "/elsewhere/two.pdf")],
  });

  it("names the workspace and the folder that holds the workspaces, and finds the attachments in the workspace", () => {
    const cmd = toCliCommand(d, "jar", undefined, { name: "billing", dir: "/srv/workspaces" });
    expect(cmd).toContain("--workspaces-dir '/srv/workspaces'");
    expect(cmd).toContain("--workspace 'billing'");
    expect(cmd).toContain("--profile 'acc'");
    expect(cmd).toContain("--attachment 'doc=/srv/workspaces/billing/attachments/invoices/one.pdf'");
    expect(cmd).toContain("--attachment 'abs=/elsewhere/two.pdf'");
    expect(cmd.split("\n")[0]).toContain("java -jar");
  });

  it("asks for the folder when it is not known", () => {
    const cmd = toCliCommand(d, "jar", undefined, { name: "billing" });
    expect(cmd.split("\n")[0]).toContain("--workspaces-dir (or SOAPCLI_WORKSPACES_DIR)");
    expect(cmd).not.toContain("--workspaces-dir '");
    expect(cmd).toContain("--attachment 'doc=invoices/one.pdf'");
  });

  it("mounts the workspaces in the container", () => {
    const cmd = toCliCommand(d, "docker", undefined, { name: "billing", dir: "/workspaces" });
    expect(cmd).toContain('-v "$PWD/workspaces:/workspaces:ro" -e SOAPCLI_WORKSPACES_DIR=/workspaces ghcr.io/marcemv90/soapcli');
    expect(cmd).toContain("--workspace 'billing'");
    expect(cmd).not.toContain("--workspaces-dir");
    expect(cmd).toContain("--attachment 'doc=/workspaces/billing/attachments/invoices/one.pdf'");
    expect(cmd.split("\n")[0]).toContain("change it to yours");
  });

  it("is the command of before when there is no workspace", () => {
    const cmd = toCliCommand(d, "jar");
    expect(cmd).not.toContain("--workspace");
    expect(cmd).toContain("--attachment 'doc=invoices/one.pdf'");
  });
});
