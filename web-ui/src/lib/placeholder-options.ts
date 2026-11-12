/** The rows of the placeholder options editor, and what is typed or pasted into it. */

export interface OptionRow {
  id: string;
  value: string;
  label: string;
}

/** The names a placeholder can have: the same shape as in a request (`___NAME:default___`). */
export const PLACEHOLDER_NAME = /^[A-Za-z_][A-Za-z0-9_.-]{0,63}$/;

let counter = 0;
export const newOptionRow = (value = "", label = ""): OptionRow => ({ id: `option-${++counter}`, value, label });

/** Lines of `value` or `value | label`, one option each; blank lines and lines without a value are skipped. */
export function parseOptionLines(text: string): OptionRow[] {
  return text
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const bar = line.indexOf("|");
      return bar < 0 ? newOptionRow(line) : newOptionRow(line.slice(0, bar).trim(), line.slice(bar + 1).trim());
    })
    .filter((r) => r.value !== "");
}

/** The values that appear in more than one row. */
export function duplicateValues(rows: OptionRow[]): string[] {
  const seen = new Set<string>();
  const repeated = new Set<string>();
  for (const r of rows) {
    if (!r.value) continue;
    if (seen.has(r.value)) repeated.add(r.value);
    seen.add(r.value);
  }
  return [...repeated];
}

/** What the server is sent: only rows with a value, and a label only when there is one. */
export function toOptions(rows: OptionRow[]): { value: string; label?: string }[] {
  return rows
    .filter((r) => r.value !== "")
    .map((r) => (r.label.trim() ? { value: r.value, label: r.label.trim() } : { value: r.value }));
}
