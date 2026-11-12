import { scan } from "./xml";

/** Characters that can be part of a content-id (RFC 2392 / RFC 2045 tokens, roughly). Same set as CidReplacer.java. */
const ID_CHARS = "A-Za-z0-9._~%@+\\-";

/**
 * The files the request refers to: every `cid:XXX` written in the *character data* of the message, once each, in order
 * of appearance. References inside attributes (`payloadId="cid:message"`), comments and processing instructions are
 * not attachments and are ignored, exactly as the server ignores them when it replaces the references.
 *
 * `cdata` says whether CDATA sections count: they do when the file is inlined as Base64, they do not with MTOM.
 */
export function findCids(xml: string, options: { cdata?: boolean } = {}): string[] {
  const found = new Set<string>();
  if (!xml) return [];
  for (const token of scan(xml)) {
    if (token.type !== "text" && !(options.cdata && token.type === "cdata")) continue;
    // A fresh regex per token: a shared /g regex keeps its position between uses.
    for (const m of token.text.matchAll(new RegExp(`(?<![${ID_CHARS}])cid:([${ID_CHARS}]+)`, "g"))) found.add(m[1]);
  }
  return [...found];
}
