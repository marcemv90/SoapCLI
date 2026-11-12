import { describe, expect, it, vi } from "vitest";
import { ServerError, fetchServerInfo, sendViaServer, toServerRequest } from "../src/lib/transports/server";
import { shellQuote, toCliCommand, toCurl } from "../src/lib/command";
import { ENV12, attachment, draft, header } from "./helpers";

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
