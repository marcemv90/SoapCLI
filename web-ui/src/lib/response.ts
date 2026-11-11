import { decodeBytes, bytesToBase64 } from "./charset";
import { maskHeader } from "./mask";
import type { BuiltRequest } from "./request";
import type { RequestView, ResponseView } from "./types";
import { clean, prettyPrint } from "./xml";

/** Multipart (MTOM) and other non-text responses must not be decoded as text. */
export function isBinaryContentType(contentType: string | null | undefined): boolean {
  if (!contentType) return false;
  const ct = contentType.toLowerCase();
  if (ct.startsWith("multipart/")) return true;
  return !(ct.includes("xml") || ct.startsWith("text/") || ct.includes("json") || ct.includes("html") || ct.includes("soap"));
}

const MAX_BINARY_PREVIEW_BYTES = 5 * 1024 * 1024;

export function requestViewOf(built: BuiltRequest): RequestView {
  const warnings = [...built.warnings];
  for (const name of built.droppedHeaders) {
    warnings.push(`The browser does not allow pages to set the '${name}' header, so it is not sent. Use the SoapCLI server mode if you need it.`);
  }
  return {
    url: built.url,
    soapVersion: built.soapVersion,
    headers: built.headers.map(([n, v]) => [n, maskHeader(n, v)]),
    contentLength: built.contentLength,
    mtom: built.mtom,
    previewXml: built.previewXml,
    attachments: built.attachments,
    warnings,
  };
}

/** Decodes and formats a response body the way the command line does. */
export function describeBody(
  bytes: Uint8Array,
  contentType: string | null,
): Pick<ResponseView, "body" | "rawBody" | "binary" | "bodyBase64"> {
  if (bytes.length === 0) return { body: "", binary: false };
  if (isBinaryContentType(contentType)) {
    return {
      body: "",
      binary: true,
      bodyBase64: bytes.length <= MAX_BINARY_PREVIEW_BYTES ? bytesToBase64(bytes) : undefined,
    };
  }
  const text = clean(decodeBytes(bytes, contentType).text);
  const pretty = prettyPrint(text);
  return { body: pretty, rawBody: pretty !== text ? text : undefined, binary: false };
}
