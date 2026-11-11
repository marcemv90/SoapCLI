/**
 * Works out which character set XML bytes are in, the way an XML parser would: byte order mark, then (for HTTP
 * responses) the `charset` of the Content-Type, then the XML declaration, then UTF-8. Port of XmlCharsets.java.
 */

const XML_DECL = /^\s*<\?xml[^>]*?encoding\s*=\s*["']([A-Za-z0-9._-]+)["']/;
const CT_CHARSET = /charset\s*=\s*"?([A-Za-z0-9._:-]+)"?/i;

/** True if the browser's TextDecoder knows this label. */
export function supportedLabel(label: string): boolean {
  try {
    new TextDecoder(label);
    return true;
  } catch {
    return false;
  }
}

export function charsetOfContentType(contentType: string | null | undefined): string | null {
  if (!contentType) return null;
  const m = CT_CHARSET.exec(contentType);
  return m && supportedLabel(m[1]) ? m[1].toLowerCase() : null;
}

/** The encoding an XML text declares in its prolog, or `utf-8`. */
export function declaredCharset(text: string): string {
  const m = XML_DECL.exec(text.slice(0, 256));
  return m && supportedLabel(m[1]) ? m[1].toLowerCase() : "utf-8";
}

export interface Decoded {
  text: string;
  charset: string;
}

export function decodeBytes(bytes: Uint8Array, contentType?: string | null): Decoded {
  let charset: string | null = null;
  if (bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) charset = "utf-8";
  else if (bytes.length >= 2 && bytes[0] === 0xfe && bytes[1] === 0xff) charset = "utf-16be";
  else if (bytes.length >= 2 && bytes[0] === 0xff && bytes[1] === 0xfe) charset = "utf-16le";
  if (!charset) charset = charsetOfContentType(contentType);
  if (!charset) {
    const head = new TextDecoder("latin1").decode(bytes.subarray(0, 256));
    const m = XML_DECL.exec(head);
    if (m && supportedLabel(m[1])) charset = m[1].toLowerCase();
  }
  charset = charset ?? "utf-8";
  // TextDecoder drops a leading BOM by default.
  return { text: new TextDecoder(charset).decode(bytes), charset };
}

const LATIN1 = new Set(["iso-8859-1", "latin1", "us-ascii", "ascii", "windows-1252", "cp1252"]);

/**
 * Encodes text the way it will be sent. Browsers only produce UTF-8 natively; ISO-8859-1 is supported by hand because
 * it is the other encoding SOAP services commonly declare. Anything else is sent as UTF-8, with a warning.
 */
export function encodeText(text: string, charset: string): { bytes: Uint8Array; warnings: string[] } {
  const cs = charset.toLowerCase();
  if (cs === "utf-8" || cs === "utf8") return { bytes: new TextEncoder().encode(text), warnings: [] };
  if (LATIN1.has(cs)) {
    const bytes = new Uint8Array(text.length);
    let lossy = false;
    let n = 0;
    for (const ch of text) {
      const cp = ch.codePointAt(0)!;
      if (cp > 0xff) {
        bytes[n++] = 0x3f; // '?'
        lossy = true;
      } else {
        bytes[n++] = cp;
      }
    }
    return {
      bytes: bytes.subarray(0, n),
      warnings: lossy ? [`Some characters cannot be represented in ${charset} and were sent as '?'.`] : [],
    };
  }
  return {
    bytes: new TextEncoder().encode(text),
    warnings: [`The request declares encoding "${charset}", but a browser can only send UTF-8; it was sent as UTF-8. Use the SoapCLI server mode for other encodings.`],
  };
}

export function bytesToBase64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

export function stringToBase64(text: string): string {
  return bytesToBase64(new TextEncoder().encode(text));
}
