import { scan } from "./xml";

/** Characters that can be part of a content-id (RFC 2392 / RFC 2045 tokens, roughly). */
const ID_CHARS = "A-Za-z0-9._~%@+\\-";

const escapeRegExp = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Replaces `cid:XXX` references in the *character data* of a SOAP message and leaves tags, attributes and comments
 * exactly as written. A reference ends where the content-id ends: `cid:message1` does not match inside
 * `cid:message10` or `cid:message1-b`. Port of CidReplacer.java.
 */
export function replaceCids(
  xml: string,
  cids: string[],
  replacement: (cid: string) => string,
  inCdata: boolean,
): { text: string; referenced: Set<string> } {
  const referenced = new Set<string>();
  if (cids.length === 0 || !xml) return { text: xml, referenced };

  const alternation = [...cids]
    .sort((a, b) => b.length - a.length)
    .map(escapeRegExp)
    .join("|");
  const pattern = new RegExp(`(?<![${ID_CHARS}])cid:(${alternation})(?![${ID_CHARS}])`, "g");
  const cache = new Map<string, string>();

  let out = "";
  for (const token of scan(xml)) {
    const eligible = token.type === "text" || (inCdata && token.type === "cdata");
    if (!eligible) {
      out += token.text;
      continue;
    }
    out += token.text.replace(pattern, (_all, cid: string) => {
      referenced.add(cid);
      let value = cache.get(cid);
      if (value === undefined) {
        value = replacement(cid);
        cache.set(cid, value);
      }
      return value;
    });
  }
  return { text: out, referenced };
}

/** Whether `cid:<cid>` appears anywhere in the document, attributes included. */
export function mentionsCid(xml: string, cid: string): boolean {
  return new RegExp(`(?<![${ID_CHARS}])cid:${escapeRegExp(cid)}(?![${ID_CHARS}])`).test(xml);
}

export const isValidCid = (cid: string) => new RegExp(`^[${ID_CHARS}]+$`).test(cid);

/** The XOP element that MTOM puts where the inline base64 would have been. */
export function xopInclude(cid: string): string {
  return `<inc:Include href="cid:${cid}" xmlns:inc="http://www.w3.org/2004/08/xop/include"/>`;
}
