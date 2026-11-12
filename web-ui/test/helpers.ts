import { emptyDraft, newId, type AttachmentInput, type RequestDraft } from "../src/lib/types";

export const ENV11 = `<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><ping/></s:Body></s:Envelope>`;
export const ENV12 = `<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body><ping/></s:Body></s:Envelope>`;

export function draft(patch: Partial<RequestDraft> = {}): RequestDraft {
  return { ...emptyDraft(), endpoint: "http://localhost:1/soap", body: ENV12, ...patch };
}

/** A file chosen on this computer: its bytes are in memory. */
export function attachment(cid: string, content: string, filename = `${cid}.bin`): AttachmentInput {
  const data = new TextEncoder().encode(content);
  return { kind: "file", id: newId(), cid, filename, size: data.length, data };
}

/** A file named by its path on the server. */
export function pathAttachment(cid: string, path: string): AttachmentInput {
  return { kind: "path", id: newId(), cid, path };
}

export function header(name: string, value: string) {
  return { id: newId(), name, value, enabled: true };
}
