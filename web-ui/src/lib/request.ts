import { bytesToBase64, declaredCharset, encodeText, stringToBase64 } from "./charset";
import { isValidCid, mentionsCid, replaceCids, xopInclude } from "./cid";
import { applyPlaceholders } from "./placeholders";
import { detectSoapVersion, SOAP_MEDIA_TYPE, type SoapVersion } from "./soap";
import type { HeaderPair, RequestDraft } from "./types";
import { clean, prettyPrint } from "./xml";

/** A mistake the user can fix: nothing was sent. */
export class RequestError extends Error {}

export interface BuiltRequest {
  url: string;
  method: "POST";
  headers: HeaderPair[];
  body: Uint8Array | Blob;
  contentLength: number;
  contentType: string;
  soapVersion: SoapVersion;
  previewXml: string;
  warnings: string[];
  mtom: boolean;
  /** `cid (size)` for each attachment. */
  attachments: string[];
  /** Header names the browser will refuse to set (they are dropped, with a warning). */
  droppedHeaders: string[];
}

const ACCEPT = "text/xml, application/soap+xml, */*;q=0.9";
const ROOT_CID = "<rootpart@soapcli>";

/** Headers a page is not allowed to set: the browser ignores them silently. */
const FORBIDDEN_EXACT = new Set([
  "accept-charset", "accept-encoding", "access-control-request-headers", "access-control-request-method",
  "connection", "content-length", "cookie", "cookie2", "date", "dnt", "expect", "host", "keep-alive", "origin",
  "referer", "set-cookie", "te", "trailer", "transfer-encoding", "upgrade", "via", "user-agent",
]);

export function isForbiddenHeader(name: string): boolean {
  const n = name.trim().toLowerCase();
  return FORBIDDEN_EXACT.has(n) || n.startsWith("proxy-") || n.startsWith("sec-");
}

/** Case-insensitive, ordered headers where a later `put` replaces an earlier one but keeps its position. */
class HeaderList {
  private readonly map = new Map<string, HeaderPair>();
  put(name: string, value: string): void {
    const key = name.toLowerCase();
    const existing = this.map.get(key);
    this.map.set(key, [existing ? existing[0] : name, value]);
  }
  entries(): HeaderPair[] {
    return [...this.map.values()];
  }
}

export function parseEndpoint(endpoint: string): string {
  const value = endpoint.trim();
  if (!value) throw new RequestError("Enter the endpoint URL.");
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new RequestError(`'${value}' is not a valid URL.`);
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new RequestError("The endpoint must be an http:// or https:// URL.");
  }
  return url.toString();
}

const quoteAction = (action: string) => action.replace(/\\/g, "\\\\").replace(/"/g, '\\"');

function unquote(value: string): string {
  const v = value.trim();
  return v.length >= 2 && v.startsWith('"') && v.endsWith('"') ? v.slice(1, -1) : v;
}

function randomBoundary(): string {
  const id = typeof crypto !== "undefined" && "randomUUID" in crypto
    ? crypto.randomUUID()
    : Math.random().toString(36).slice(2) + Date.now().toString(36);
  return `----=_soapcli_${id}`;
}

/** Turns the editor state into the bytes and headers of the HTTP request. Pure: it touches no network. */
export function buildRequest(draft: RequestDraft): BuiltRequest {
  const url = parseEndpoint(draft.endpoint);
  const warnings: string[] = [];

  let text = clean(draft.body);
  if (!text.trim()) throw new RequestError("The request body is empty.");

  const placeholders = applyPlaceholders(text, draft.args);
  for (const name of Object.keys(draft.args)) {
    if (!placeholders.used.has(name)) {
      warnings.push(`The value for '${name}' is not used by any ___${name}:default___ placeholder in the request.`);
    }
  }
  text = placeholders.text;

  let version: SoapVersion;
  if (draft.soapVersion === "auto") {
    const detected = detectSoapVersion(text);
    if (!detected) warnings.push("Could not detect the SOAP version from the request; assuming SOAP 1.2. Choose one explicitly if that is wrong.");
    version = detected ?? "1.2";
  } else {
    version = draft.soapVersion;
  }

  const action = draft.soapAction.trim() ? unquote(draft.soapAction) : null;
  const actionParam = version === "1.2" && action !== null ? `; action="${quoteAction(action)}"` : "";
  const media = SOAP_MEDIA_TYPE[version];

  const charset = declaredCharset(text);
  const attachments = draft.attachments.filter((a) => a.cid.trim() !== "");
  for (const a of attachments) {
    if (!isValidCid(a.cid.trim())) {
      throw new RequestError(`'${a.cid}' is not a valid CID: use letters, digits and . _ - ~ % @ +`);
    }
  }
  const cids = attachments.map((a) => a.cid.trim());
  const dataOf = new Map(attachments.map((a) => [a.cid.trim(), a]));

  let body: Uint8Array | Blob;
  let contentLength: number;
  let contentType: string;
  let previewXml: string;
  let mtom = false;

  const warnUnreferenced = (referenced: Set<string>, onlyText: boolean) => {
    for (const cid of cids) {
      if (referenced.has(cid)) continue;
      if (!mentionsCid(text, cid)) {
        warnings.push(`Attachment '${cid}' is not referenced as cid:${cid} in the request.`);
      } else if (onlyText) {
        warnings.push(`cid:${cid} only appears in attributes, which inline mode does not replace (turn on MTOM to send the file as a MIME part).`);
      }
    }
  };

  if (attachments.length === 0) {
    if (draft.mtom) warnings.push("MTOM has no effect without attachments.");
    const encoded = encodeText(text, charset);
    warnings.push(...encoded.warnings);
    body = encoded.bytes;
    contentLength = encoded.bytes.length;
    contentType = `${media}; charset=${charset}${actionParam}`;
    previewXml = text;
  } else if (draft.mtom) {
    mtom = true;
    const xop = replaceCids(text, cids, xopInclude, false);
    warnUnreferenced(xop.referenced, false);
    const boundary = randomBoundary();
    const encoded = encodeText(xop.text, charset);
    warnings.push(...encoded.warnings);
    const rootType = `application/xop+xml; charset=${charset.toUpperCase()}; type="${media}"${actionParam}`;
    const enc = new TextEncoder();
    const parts: BlobPart[] = [
      enc.encode(`--${boundary}\r\nContent-Type: ${rootType}\r\nContent-Transfer-Encoding: 8bit\r\nContent-ID: ${ROOT_CID}\r\n\r\n`),
      encoded.bytes as BlobPart,
      enc.encode("\r\n"),
    ];
    for (const a of attachments) {
      parts.push(
        enc.encode(`--${boundary}\r\nContent-Type: application/octet-stream\r\nContent-ID: <${a.cid.trim()}>\r\nContent-Transfer-Encoding: binary\r\n\r\n`),
        a.data as BlobPart,
        enc.encode("\r\n"),
      );
    }
    parts.push(enc.encode(`--${boundary}--\r\n`));
    const blob = new Blob(parts);
    body = blob;
    contentLength = blob.size;
    contentType = `multipart/related; type="application/xop+xml"; start="${ROOT_CID}"; start-info="${media}"${actionParam}; boundary="${boundary}"`;
    previewXml = xop.text;
  } else {
    const inlined = replaceCids(text, cids, (cid) => bytesToBase64(dataOf.get(cid)!.data), true);
    warnUnreferenced(inlined.referenced, true);
    const shown = replaceCids(text, cids, (cid) => {
      const a = dataOf.get(cid)!;
      return `[base64 of ${a.filename || cid}, ${a.data.length} bytes]`;
    }, true);
    const encoded = encodeText(inlined.text, charset);
    warnings.push(...encoded.warnings);
    body = encoded.bytes;
    contentLength = encoded.bytes.length;
    contentType = `${media}; charset=${charset}${actionParam}`;
    previewXml = shown.text;
  }

  const headers = new HeaderList();
  headers.put("Content-Type", contentType);
  headers.put("Accept", ACCEPT);
  if (version === "1.1") headers.put("SOAPAction", `"${action ?? ""}"`); // SOAP 1.1 requires it, even if empty
  if (draft.auth.kind === "basic" && (draft.auth.user || draft.auth.password)) {
    headers.put("Authorization", `Basic ${stringToBase64(`${draft.auth.user}:${draft.auth.password}`)}`);
  } else if (draft.auth.kind === "bearer" && draft.auth.token.trim()) {
    headers.put("Authorization", `Bearer ${draft.auth.token.trim()}`);
  }
  const droppedHeaders: string[] = [];
  for (const h of draft.headers) {
    if (!h.enabled || !h.name.trim()) continue;
    headers.put(h.name.trim(), h.value); // user headers go last so they can override any default above
    if (isForbiddenHeader(h.name)) droppedHeaders.push(h.name.trim());
  }

  return {
    url,
    method: "POST",
    headers: headers.entries(),
    body,
    contentLength,
    contentType,
    soapVersion: version,
    previewXml: prettyPrint(previewXml),
    warnings,
    mtom,
    attachments: attachments.map((a) => `${a.cid.trim()} (${a.data.length} bytes)`),
    droppedHeaders,
  };
}
