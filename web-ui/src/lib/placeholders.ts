/** `___NAME:default___` markers in the request text, the same syntax as the command line tool. */

const NAME = "[A-Za-z_][A-Za-z0-9_.\\-]*";

function pattern(): RegExp {
  // A fresh object every time: a shared /g regex keeps state between uses.
  return new RegExp(`___(${NAME}):([\\s\\S]*?)___`, "g");
}

export interface PlaceholderInfo {
  name: string;
  defaultValue: string;
}

/** The placeholders in a text, once each, in order of appearance. */
export function findPlaceholders(text: string): PlaceholderInfo[] {
  const seen = new Map<string, PlaceholderInfo>();
  for (const m of text.matchAll(pattern())) {
    if (!seen.has(m[1])) seen.set(m[1], { name: m[1], defaultValue: m[2] });
  }
  return [...seen.values()];
}

/** Replaces each placeholder with its value if one is given, else with its default. */
export function applyPlaceholders(
  text: string,
  values: Record<string, string>,
): { text: string; used: Set<string> } {
  const used = new Set<string>();
  const result = text.replace(pattern(), (_all, name: string, def: string) => {
    if (Object.hasOwn(values, name)) {
      used.add(name);
      return values[name];
    }
    return def;
  });
  return { text: result, used };
}
