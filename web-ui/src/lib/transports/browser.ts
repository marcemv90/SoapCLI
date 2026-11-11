import { RequestError, isForbiddenHeader, type BuiltRequest } from "../request";
import { maskHeader } from "../mask";
import { describeBody, requestViewOf } from "../response";
import type { HeaderPair, ResponseView } from "../types";

/** The call did not complete. `kind` tells the UI what to suggest. */
export class TransportError extends Error {
  constructor(
    readonly kind: "timeout" | "network" | "cancelled",
    message: string,
    readonly hints: string[] = [],
  ) {
    super(message);
  }
}

export interface BrowserSendOptions {
  timeoutSeconds: number;
  followRedirects: boolean;
}

const isLoopback = (host: string) => host === "localhost" || host === "[::1]" || host.startsWith("127.");

/** Why a `fetch` that fails with a bare TypeError usually failed. The browser deliberately says no more than that. */
export function networkHints(url: string, pageProtocol?: string): string[] {
  const hints = [
    "CORS: browsers only let a page read an answer from another origin if the server allows it " +
      "(Access-Control-Allow-Origin, and an answer to the preflight request that text/xml and SOAPAction trigger). " +
      "Most SOAP servers do not. Use the 'Via SoapCLI server' transport, or enable CORS on the service.",
  ];
  let parsed: URL | null = null;
  try {
    parsed = new URL(url);
  } catch {
    /* already validated by the builder */
  }
  if (parsed) {
    if (pageProtocol === "https:" && parsed.protocol === "http:" && !isLoopback(parsed.hostname)) {
      hints.unshift("Mixed content: this page was loaded over HTTPS, so the browser blocks calls to an http:// URL.");
    }
    if (parsed.protocol === "https:") {
      hints.push("If the server's certificate is self-signed or from a private CA, the browser rejects it: open the URL in a tab and accept it, or use the server transport with the insecure option.");
    }
  }
  hints.push("Otherwise the host may simply be unreachable from this machine (DNS, firewall, VPN).");
  return hints;
}

/**
 * Sends the request straight from the page. It runs on the user's machine, so it reaches whatever the user's
 * network reaches, including localhost and private addresses; the price is that the browser enforces CORS.
 */
export async function sendFromBrowser(
  built: BuiltRequest,
  options: BrowserSendOptions,
  cancel?: AbortSignal,
): Promise<ResponseView> {
  const headers = new Headers();
  for (const [name, value] of built.headers) {
    if (isForbiddenHeader(name)) continue; // the browser would ignore it (and log an error)
    try {
      headers.append(name, value);
    } catch {
      throw new RequestError(`'${name}' is not a valid header: names use letters, digits and - _ ; values must be plain text.`);
    }
  }

  const controller = new AbortController();
  let timedOut = false;
  const timer = options.timeoutSeconds > 0
    ? setTimeout(() => {
        timedOut = true;
        controller.abort();
      }, options.timeoutSeconds * 1000)
    : undefined;
  cancel?.addEventListener("abort", () => controller.abort());

  const started = performance.now();
  try {
    const response = await fetch(built.url, {
      method: built.method,
      headers,
      body: built.body as BodyInit,
      credentials: "omit",
      cache: "no-store",
      mode: "cors",
      // "manual" so that a 301/302 is not silently turned into a GET on another URL.
      redirect: options.followRedirects ? "follow" : "manual",
      referrerPolicy: "no-referrer",
      signal: controller.signal,
    });
    const bytes = new Uint8Array(await response.arrayBuffer());
    const elapsedMs = Math.round(performance.now() - started);
    const contentType = response.headers.get("content-type");
    const responseHeaders: HeaderPair[] = [];
    response.headers.forEach((value, name) => responseHeaders.push([name, maskHeader(name, value)]));

    const notes: string[] = [];
    const opaqueRedirect = response.type === "opaqueredirect";
    if (opaqueRedirect) {
      notes.push("The server answered with a redirect. A browser does not let a page see where to; call the final URL directly, tick 'Follow redirects', or use the server transport.");
    } else if (response.redirected) {
      notes.push(`The browser followed a redirect; the answer comes from ${response.url}. (Browsers turn a redirected POST into a GET for 301/302.)`);
    }

    return {
      via: "browser",
      status: response.status,
      statusText: opaqueRedirect ? "Redirect (not followed)" : response.statusText,
      elapsedMs,
      url: response.url || built.url,
      contentType,
      size: bytes.length,
      headers: responseHeaders,
      ...describeBody(bytes, contentType),
      opaqueRedirect,
      notes,
      request: requestViewOf(built),
    };
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      if (timedOut) throw new TransportError("timeout", `The server did not answer within ${options.timeoutSeconds} s.`);
      throw new TransportError("cancelled", "The request was cancelled.");
    }
    if (error instanceof RequestError) throw error;
    throw new TransportError(
      "network",
      "The browser could not complete the request.",
      networkHints(built.url, typeof location === "undefined" ? undefined : location.protocol),
    );
  } finally {
    if (timer !== undefined) clearTimeout(timer);
  }
}

/**
 * Asks whether anything answers at this address at all, ignoring CORS: a `no-cors` request cannot be read, but it
 * only succeeds if the server was reached. Distinguishes "blocked by CORS" from "unreachable" after a failed call.
 */
export async function probeReachable(url: string, timeoutMs = 8000): Promise<boolean> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    await fetch(url, {
      method: "HEAD",
      mode: "no-cors",
      cache: "no-store",
      credentials: "omit",
      referrerPolicy: "no-referrer",
      signal: controller.signal,
    });
    return true;
  } catch {
    return false;
  } finally {
    clearTimeout(timer);
  }
}
