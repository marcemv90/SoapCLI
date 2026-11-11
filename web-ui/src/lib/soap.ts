import { scan, tagName, isEndTag } from "./xml";

export type SoapVersion = "1.1" | "1.2";

export const SOAP_NAMESPACE: Record<SoapVersion, string> = {
  "1.1": "http://schemas.xmlsoap.org/soap/envelope/",
  "1.2": "http://www.w3.org/2003/05/soap-envelope",
};

/** The media type a SOAP message of each version is sent as. */
export const SOAP_MEDIA_TYPE: Record<SoapVersion, string> = {
  "1.1": "text/xml",
  "1.2": "application/soap+xml",
};

/** Reads the SOAP version from the namespace of the root Envelope element; `null` if it is not recognisable. */
export function detectSoapVersion(xml: string): SoapVersion | null {
  for (const t of scan(xml)) {
    if (t.type !== "tag") continue;
    if (isEndTag(t)) return null;
    const name = tagName(t) ?? "";
    const colon = name.indexOf(":");
    const prefix = colon < 0 ? "" : name.slice(0, colon);
    const local = colon < 0 ? name : name.slice(colon + 1);
    if (local !== "Envelope") return null;
    const declared = new Map<string, string>();
    for (const m of t.text.matchAll(/\sxmlns(?::([A-Za-z0-9_.-]+))?\s*=\s*(?:"([^"]*)"|'([^']*)')/g)) {
      declared.set(m[1] ?? "", m[2] ?? m[3] ?? "");
    }
    const ns = declared.get(prefix);
    for (const v of ["1.1", "1.2"] as SoapVersion[]) {
      if (SOAP_NAMESPACE[v] === ns) return v;
    }
    return null;
  }
  return null;
}
