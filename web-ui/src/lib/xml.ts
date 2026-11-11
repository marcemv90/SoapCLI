/**
 * Text-level XML helpers, a port of the Java core's XmlScanner / XmlFormat: no DOM and no DTD processing, every
 * character of the input is kept, and only whitespace *between* elements is ever changed.
 */

export type TokenType = "text" | "tag" | "comment" | "cdata" | "pi" | "doctype";

export interface Token {
  type: TokenType;
  start: number;
  end: number;
  text: string;
}

function endOf(xml: string, terminator: string, from: number): number {
  const j = xml.indexOf(terminator, from);
  return j < 0 ? -1 : j + terminator.length;
}

/** End (exclusive) of a start/end tag, honouring `>` inside quoted attribute values. */
function tagEnd(xml: string, start: number): number {
  let quote = "";
  for (let i = start + 1; i < xml.length; i++) {
    const c = xml[i];
    if (quote) {
      if (c === quote) quote = "";
    } else if (c === '"' || c === "'") {
      quote = c;
    } else if (c === ">") {
      return i + 1;
    } else if (c === "<") {
      return -1; // a new tag starts before this one closed: malformed
    }
  }
  return -1;
}

/** End (exclusive) of a `<!DOCTYPE ...>` including an internal subset in square brackets. */
function doctypeEnd(xml: string, start: number): number {
  let depth = 0;
  let quote = "";
  for (let i = start + 2; i < xml.length; i++) {
    const c = xml[i];
    if (quote) {
      if (c === quote) quote = "";
    } else if (c === '"' || c === "'") {
      quote = c;
    } else if (c === "[") {
      depth++;
    } else if (c === "]") {
      depth--;
    } else if (c === ">" && depth <= 0) {
      return i + 1;
    }
  }
  return -1;
}

/** Splits XML into tokens without validating it. Concatenating `token.text` gives back the input. */
export function scan(xml: string): Token[] {
  const tokens: Token[] = [];
  const n = xml.length;
  let i = 0;
  const push = (type: TokenType, start: number, end: number) =>
    tokens.push({ type, start, end, text: xml.slice(start, end) });

  while (i < n) {
    if (xml[i] !== "<") {
      let j = xml.indexOf("<", i);
      if (j < 0) j = n;
      push("text", i, j);
      i = j;
      continue;
    }
    let type: TokenType;
    let end: number;
    if (xml.startsWith("<!--", i)) {
      type = "comment";
      end = endOf(xml, "-->", i + 4);
    } else if (xml.startsWith("<![CDATA[", i)) {
      type = "cdata";
      end = endOf(xml, "]]>", i + 9);
    } else if (xml.startsWith("<?", i)) {
      type = "pi";
      end = endOf(xml, "?>", i + 2);
    } else if (xml.startsWith("<!", i)) {
      type = "doctype";
      end = doctypeEnd(xml, i);
    } else {
      type = "tag";
      end = tagEnd(xml, i);
    }
    if (end < 0) {
      push("text", i, n); // unterminated construct: keep the rest as plain text
      break;
    }
    push(type, i, end);
    i = end;
  }
  return tokens;
}

export const isEndTag = (t: Token) => t.type === "tag" && t.text[1] === "/";
export const isSelfClosing = (t: Token) => t.type === "tag" && !isEndTag(t) && t.text[t.text.length - 2] === "/";
export const isBlank = (t: Token) => t.type === "text" && t.text.trim() === "";

/** The qualified name of a tag token, e.g. `soapenv:Envelope`. */
export function tagName(t: Token): string | null {
  if (t.type !== "tag") return null;
  let i = 1;
  if (t.text[i] === "/") i++;
  let j = i;
  while (j < t.text.length - 1) {
    const c = t.text[j];
    if (/\s/.test(c) || c === "/" || c === ">") break;
    j++;
  }
  return t.text.slice(i, j);
}

/** Escapes the five predefined XML entities. */
export function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

/** Removes a leading byte order mark and the control characters XML 1.0 forbids. */
export function clean(xml: string): string {
  if (!xml) return xml;
  let out = xml.charCodeAt(0) === 0xfeff ? xml.slice(1) : xml;
  // eslint-disable-next-line no-control-regex
  out = out.replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, "");
  return out;
}

// ---- pretty printing ----------------------------------------------------------------------------------------------

interface Node {
  token: Token;
  end: number;
  closeStart: number;
  element: boolean;
  children: Node[];
}

function parse(xml: string): Node[] | null {
  const roots: Node[] = [];
  const stack: Node[] = [];
  for (const t of scan(xml)) {
    const target = stack.length === 0 ? roots : stack[stack.length - 1].children;
    if (t.type === "tag") {
      if (isEndTag(t)) {
        const open = stack.pop();
        if (!open || tagName(open.token) !== tagName(t)) return null;
        open.end = t.end;
        open.closeStart = t.start;
      } else {
        const node: Node = { token: t, end: t.end, closeStart: t.end, element: true, children: [] };
        target.push(node);
        if (!isSelfClosing(t)) stack.push(node);
      }
    } else {
      target.push({ token: t, end: t.end, closeStart: t.end, element: false, children: [] });
    }
  }
  return stack.length === 0 ? roots : null;
}

const significantText = (n: Node) =>
  !n.element && ((n.token.type === "text" && !isBlank(n.token)) || n.token.type === "cdata");

function print(node: Node, depth: number, xml: string, out: string[]): void {
  const pad = "  ".repeat(depth);
  if (!node.element) {
    if (node.token.type === "text") {
      if (!isBlank(node.token)) out.push(pad + node.token.text.trim() + "\n");
      return;
    }
    out.push(pad + node.token.text + "\n");
    return;
  }
  if (isSelfClosing(node.token) || node.children.length === 0) {
    out.push(pad + xml.slice(node.token.start, node.end) + "\n");
    return;
  }
  let hasSignificantText = false;
  let hasStructure = false;
  for (const child of node.children) {
    if (significantText(child)) hasSignificantText = true;
    else if (child.element || child.token.type !== "text") hasStructure = true;
  }
  // Text-only, whitespace-only or mixed content: the whitespace is data, so keep the element verbatim.
  if (hasSignificantText || !hasStructure) {
    out.push(pad + xml.slice(node.token.start, node.end) + "\n");
    return;
  }
  out.push(pad + node.token.text + "\n");
  for (const child of node.children) print(child, depth + 1, xml, out);
  out.push(pad + xml.slice(node.closeStart, node.end) + "\n");
}

/** Pretty prints XML; returns the input unchanged if it is empty or not well-formed. */
export function prettyPrint(xml: string): string {
  if (!xml || xml.trim() === "") return xml;
  const normalized = xml.replace(/\r\n/g, "\n");
  const roots = parse(normalized);
  if (!roots || !roots.some((n) => n.element)) return xml; // malformed, or not XML at all
  const out: string[] = [];
  for (const node of roots) print(node, 0, normalized, out);
  return out.join("");
}
