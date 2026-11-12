/**
 * The model behind the profile form. A profile is edited as the JSON object it is in the file (its own settings, not
 * merged with what it extends), so that a setting the form does not know is kept as it is. Everything here is pure.
 */

export type Definition = Record<string, unknown>;
export type Path = string[];

/** What the server shows instead of a secret. Left as it is, it means "keep the saved value". */
export const MASK = "****";

/** The names the server treats as secrets (same pattern as SecretMasker.java). */
const SECRET_NAME = /authorization|authbasic|authbearer|cookie|token|secret|password|passwd|pkpass|api[-_]?key|credential/i;
export const isSecretName = (name: string): boolean => SECRET_NAME.test(name);

/** The settings of a profile that SoapCLI knows (the others are ignored by it, and kept by the form). */
export const KNOWN_KEYS = [
  "extends", "endpoint", "requestFile", "theme", "soapVersion", "soapAction", "mtom", "timeout", "connectTimeout",
  "followRedirects", "headers", "args", "attachments", "authBasic", "authBearer", "keystore", "truststore", "insecure", "proxy",
];

export const THEMES = ["darcula", "dracula", "gruvbox-dark", "gruvbox-light", "monokai", "nord", "solarized-dark", "solarized-light"];

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

export const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

/** The settings of the profile that the form does not know about. */
export function unknownKeys(def: Definition): string[] {
  return Object.keys(def).filter((k) => !KNOWN_KEYS.includes(k));
}

export function getAt(def: Definition, path: Path): unknown {
  let current: unknown = def;
  for (const key of path) {
    if (!isObject(current)) return undefined;
    current = current[key];
  }
  return current;
}

/**
 * A copy of `def` with the value at `path` set; `undefined` removes it, and a group left with nothing in it goes too.
 * A group that was `null` (an inherited group switched off) becomes an object again when something is set inside it.
 */
export function setAt(def: Definition, path: Path, value: unknown): Definition {
  const [head, ...rest] = path;
  const next: Definition = { ...def };
  if (rest.length === 0) {
    if (value === undefined) delete next[head];
    else next[head] = value;
    return next;
  }
  const child = isObject(next[head]) ? (next[head] as Definition) : {};
  const updated = setAt(child, rest, value);
  if (Object.keys(updated).length === 0 && value === undefined) delete next[head];
  else next[head] = updated;
  return next;
}

/** The value at `path` as text: `""` when there is none, or when it is `null`. */
export function textAt(def: Definition, path: Path): string {
  const v = getAt(def, path);
  return typeof v === "string" ? v : typeof v === "number" || typeof v === "boolean" ? String(v) : "";
}

/** `null` is how a profile switches off what it inherits. */
export const isNullAt = (def: Definition, path: Path): boolean => getAt(def, path) === null;

/** Text to the value to store: an empty field is "not set", whole numbers are numbers, and `${VAR}` stays text. */
export function setText(def: Definition, path: Path, text: string): Definition {
  return setAt(def, path, text === "" ? undefined : text);
}

export function setNumberOrText(def: Definition, path: Path, text: string): Definition {
  const trimmed = text.trim();
  if (trimmed === "") return setAt(def, path, undefined);
  return setAt(def, path, /^-?\d+$/.test(trimmed) ? Number(trimmed) : text);
}

export type Tri = { state: "unset" } | { state: "true" } | { state: "false" } | { state: "other"; raw: string };

/** A yes/no setting that may also be left to the inherited value, or written as text (`${VAR}`). */
export function triAt(def: Definition, path: Path): Tri {
  const v = getAt(def, path);
  if (v === undefined || v === null || v === "") return { state: "unset" };
  if (v === true || (typeof v === "string" && v.trim().toLowerCase() === "true")) return { state: "true" };
  if (v === false || (typeof v === "string" && v.trim().toLowerCase() === "false")) return { state: "false" };
  return { state: "other", raw: String(v) };
}

export function setTri(def: Definition, path: Path, choice: "unset" | "true" | "false"): Definition {
  return setAt(def, path, choice === "unset" ? undefined : choice === "true");
}

// ---- name/value tables (headers, variables, attachments) ---------------------------------------------------------------

export interface Row {
  id: string;
  name: string;
  value: string;
  /** The profile has `null` here: it removes what it inherits under that name. */
  removed?: boolean;
  /** The value is a secret the server did not send: it is kept unless a new one is typed. */
  saved?: boolean;
}

let rowCounter = 0;
export const newRowId = () => `row-${++rowCounter}`;

export function rowsAt(def: Definition, key: string): Row[] {
  const table = def[key];
  if (!isObject(table)) return [];
  return Object.entries(table).map(([name, v]) =>
    v === null
      ? { id: newRowId(), name, value: "", removed: true }
      : { id: newRowId(), name, value: typeof v === "string" ? v : String(v), saved: v === MASK && isSecretName(name) },
  );
}

/** The table as stored: rows without a name are dropped, and for a repeated name the last row counts. */
export function tableOf(rows: Row[]): Record<string, string | null> | undefined {
  const table: Record<string, string | null> = {};
  for (const r of rows) {
    const name = r.name.trim();
    if (name) table[name] = r.removed ? null : r.value;
  }
  return Object.keys(table).length === 0 ? undefined : table;
}

/** The names that appear in more than one row. */
export function repeatedNames(rows: Row[]): string[] {
  const seen = new Set<string>();
  const repeated = new Set<string>();
  for (const r of rows) {
    const name = r.name.trim();
    if (!name) continue;
    if (seen.has(name)) repeated.add(name);
    seen.add(name);
  }
  return [...repeated];
}

/** Whether what is in the form differs from what was loaded. */
export const isDirty = (current: Definition, initial: Definition): boolean => JSON.stringify(current) !== JSON.stringify(initial);

/** A new profile starts from nothing (or from what it inherits, once chosen). */
export const emptyDefinition = (): Definition => ({});

export const PROFILE_NAME = /^[A-Za-z0-9._-]{1,64}$/;
