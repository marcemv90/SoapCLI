import http from "node:http";
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest";
import { buildRequest } from "../src/lib/request";
import { TransportError, networkHints, probeReachable, sendFromBrowser } from "../src/lib/transports/browser";
import { ServerError, sendViaServer, toServerRequest, fetchServerInfo } from "../src/lib/transports/server";
import { toCliCommand, toCurl, shellQuote } from "../src/lib/command";
import { maskHeader } from "../src/lib/mask";
import { ENV11, ENV12, attachment, draft, header } from "./helpers";

interface Seen { method?: string; url?: string; headers: http.IncomingHttpHeaders; body: Buffer }
const seen: Seen[] = [];
let server: http.Server;
let base: string;

beforeAll(async () => {
  server = http.createServer((req, res) => {
    const chunks: Buffer[] = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      seen.push({ method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks) });
      const path = req.url ?? "/";
      if (path === "/fault") {
        res.writeHead(500, { "Content-Type": "text/xml; charset=utf-8" }).end("<Fault>boom</Fault>");
      } else if (path === "/latin1") {
        res.writeHead(200, { "Content-Type": "text/xml; charset=ISO-8859-1" }).end(Buffer.from("<a>ñandú</a>", "latin1"));
      } else if (path === "/multipart") {
        res.writeHead(200, { "Content-Type": 'multipart/related; boundary=b; type="application/xop+xml"' }).end("--b\r\n\r\n<a/>\r\n--b--\r\n");
      } else if (path === "/redirect") {
        res.writeHead(302, { Location: `${base}/landed` }).end();
      } else if (path === "/slow") {
        setTimeout(() => res.writeHead(200).end("<slow/>"), 3000);
      } else if (path === "/empty") {
        res.writeHead(202).end();
      } else {
        res.writeHead(200, { "Content-Type": "text/xml; charset=utf-8", "Set-Cookie": "sid=topsecret" }).end("<a><b>1</b></a>");
      }
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(() => {
  server.closeAllConnections();
  server.close();
});

const opts = { timeoutSeconds: 10, followRedirects: false };

describe("sendFromBrowser", () => {
  it("posts the built request and returns a formatted, masked view", async () => {
    seen.length = 0;
    const d = draft({ endpoint: `${base}/ok`, body: ENV11, soapAction: "urn:x", auth: { kind: "basic", user: "alice", password: "S3cr3t", token: "" } });
    const view = await sendFromBrowser(buildRequest(d), opts);

    expect(view.via).toBe("browser");
    expect(view.status).toBe(200);
    expect(view.body).toBe("<a>\n  <b>1</b>\n</a>\n");
    expect(view.rawBody).toBe("<a><b>1</b></a>");
    expect(view.size).toBe(15);
    const sent = seen[0];
    expect(sent.method).toBe("POST");
    expect(sent.headers["content-type"]).toBe("text/xml; charset=utf-8");
    expect(sent.headers["soapaction"]).toBe('"urn:x"');
    expect(sent.headers["authorization"]).toMatch(/^Basic /);
    expect(sent.body.toString()).toBe(ENV11);

    expect(view.request.headers.find(([n]) => n === "Authorization")![1]).toBe("Basic ****");
    expect(JSON.stringify(view)).not.toContain("S3cr3t");
    expect(JSON.stringify(view)).not.toContain(Buffer.from("alice:S3cr3t").toString("base64"));
    expect(view.headers.find(([n]) => n.toLowerCase() === "set-cookie")![1]).toBe("****");
  });

  it("does not send headers a browser would refuse", async () => {
    seen.length = 0;
    await sendFromBrowser(buildRequest(draft({ endpoint: `${base}/ok`, headers: [header("Cookie", "a=b"), header("X-Ok", "1")] })), opts);
    expect(seen[0].headers["cookie"]).toBeUndefined();
    expect(seen[0].headers["x-ok"]).toBe("1");
  });

  it("returns SOAP faults as data, with their status", async () => {
    const view = await sendFromBrowser(buildRequest(draft({ endpoint: `${base}/fault` })), opts);
    expect(view.status).toBe(500);
    expect(view.body).toBe("<Fault>boom</Fault>\n");
  });

  it("decodes responses in other charsets", async () => {
    const view = await sendFromBrowser(buildRequest(draft({ endpoint: `${base}/latin1` })), opts);
    expect(view.body).toBe("<a>ñandú</a>\n");
  });

  it("does not decode multipart responses as text", async () => {
    const view = await sendFromBrowser(buildRequest(draft({ endpoint: `${base}/multipart` })), opts);
    expect(view.binary).toBe(true);
    expect(view.body).toBe("");
    expect(Buffer.from(view.bodyBase64!, "base64").toString()).toContain("--b");
  });

  it("handles empty answers", async () => {
    const view = await sendFromBrowser(buildRequest(draft({ endpoint: `${base}/empty` })), opts);
    expect(view.status).toBe(202);
    expect(view.body).toBe("");
  });

  it("does not follow redirects unless asked", async () => {
    seen.length = 0;
    const view = await sendFromBrowser(buildRequest(draft({ endpoint: `${base}/redirect` })), opts);
    expect(view.status).toBe(302);
    expect(seen.map((s) => s.url)).toEqual(["/redirect"]);
  });

  it("times out with a clear error", async () => {
    const started = Date.now();
    await expect(sendFromBrowser(buildRequest(draft({ endpoint: `${base}/slow` })), { timeoutSeconds: 1, followRedirects: false }))
      .rejects.toMatchObject({ kind: "timeout" });
    expect(Date.now() - started).toBeLessThan(2500);
  });

  it("can be cancelled", async () => {
    const controller = new AbortController();
    const promise = sendFromBrowser(buildRequest(draft({ endpoint: `${base}/slow` })), opts, controller.signal);
    setTimeout(() => controller.abort(), 100);
    await expect(promise).rejects.toMatchObject({ kind: "cancelled" });
  });

  it("explains network failures, CORS included", async () => {
    const error = await sendFromBrowser(buildRequest(draft({ endpoint: "http://127.0.0.1:1/x" })), opts).catch((e) => e);
    expect(error).toBeInstanceOf(TransportError);
    expect(error.kind).toBe("network");
    expect(error.hints.join(" ")).toContain("CORS");
    expect(await probeReachable("http://127.0.0.1:1/x", 2000)).toBe(false);
    expect(await probeReachable(`${base}/ok`, 2000)).toBe(true);
  });

  it("adds mixed-content and certificate hints when they apply", () => {
    expect(networkHints("http://intranet.corp/soap", "https:")[0]).toContain("Mixed content");
    expect(networkHints("http://localhost/soap", "https:").join(" ")).not.toContain("Mixed content");
    expect(networkHints("https://x.corp/soap", "http:").join(" ")).toContain("certificate");
  });
});

describe("server transport", () => {
  it("maps a draft to the API request without leaking disabled headers", () => {
    const d = draft({
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

  it("turns the API answer into a response view", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      status: 500, statusMessage: "Internal Server Error", elapsedMillis: 12, uri: "http://t/soap", contentType: "text/xml",
      bodySize: 5, binary: false, body: "<f/>\n", headers: [{ name: "Content-Type", value: "text/xml" }],
      request: { endpoint: "http://t/soap", soapVersion: "1.1", headers: [{ name: "Authorization", value: "Basic ****" }],
        contentLength: 9, mtom: false, previewXml: "<x/>", attachments: [], warnings: ["w"] },
    }), { status: 200 })));
    const view = await sendViaServer(draft());
    expect(view.via).toBe("server");
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

  it("writes curl for non-MTOM requests only", () => {
    const plain = draft({ endpoint: "https://x/soap", body: ENV12, auth: { kind: "basic", user: "u", password: "p", token: "" } });
    const curl = toCurl(plain, buildRequest(plain))!;
    expect(curl).toContain("curl -sS -X POST 'https://x/soap'");
    expect(curl).toContain('-u "$SOAP_CREDENTIALS"');
    expect(curl).toContain("--data-binary @request.xml");
    expect(curl).not.toContain("Basic ");
    expect(toCurl(d, buildRequest(draft({ body: ENV12.replace("<ping/>", "<v>cid:m</v>"), mtom: true, attachments: [attachment("m", "x")] })))).toBeNull();
  });

  it("masks credential headers", () => {
    expect(maskHeader("Authorization", "Basic abc")).toBe("Basic ****");
    expect(maskHeader("Proxy-Authorization", "Bearer abc")).toBe("Bearer ****");
    expect(maskHeader("Cookie", "sid=1")).toBe("****");
    expect(maskHeader("X-Api-Key", "k")).toBe("****");
    expect(maskHeader("Content-Type", "text/xml")).toBe("text/xml");
  });
});
