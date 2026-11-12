import { useId, useState, type ReactNode } from "react";
import { NativeSelect } from "@/components/common/controls";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { EyeCloseIcon, EyeIcon, PlusIcon, TrashBinIcon } from "@/icons";
import { MASK, isSecretName, newRowId, repeatedNames, type Row, type Tri } from "@/lib/profile-form";

/** A titled group of fields. */
export function FormSection({ title, description, children }: { title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <section aria-label={title} className="flex flex-col gap-4 rounded-xl border border-gray-200 p-4 dark:border-gray-800">
      <div>
        <h3 className="text-sm font-semibold text-gray-800 dark:text-white/90">{title}</h3>
        {description && <p className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">{description}</p>}
      </div>
      {children}
    </section>
  );
}

export function Field({ label, hint, htmlFor, children }: { label: string; hint?: ReactNode; htmlFor: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <label htmlFor={htmlFor} className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">{label}</label>
      {children}
      {hint && <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{hint}</p>}
    </div>
  );
}

export function TextField({ label, hint, value, onChange, placeholder, mono, list }: {
  label: string;
  hint?: ReactNode;
  value: string;
  onChange: (value: string) => void;
  /** What is used when this is left empty (for instance, what the profile inherits). */
  placeholder?: string;
  mono?: boolean;
  list?: string;
}) {
  const id = useId();
  return (
    <Field label={label} hint={hint} htmlFor={id}>
      <Input id={id} list={list} className={mono ? "font-mono text-xs" : undefined} spellCheck={false} autoComplete="off"
        placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} />
    </Field>
  );
}

export function SelectField({ label, hint, value, onChange, options }: {
  label: string;
  hint?: ReactNode;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  const id = useId();
  const known = options.some((o) => o.value === value);
  return (
    <Field label={label} hint={hint} htmlFor={id}>
      <NativeSelect id={id} className="w-full" value={value} onChange={(e) => onChange(e.target.value)}>
        {!known && <option value={value}>{value}</option>}
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </NativeSelect>
    </Field>
  );
}

/** Yes, no, or whatever the profile inherits; a value written as text (`${VAR}`) is shown and kept. */
export function TriField({ label, hint, tri, onChange, inherited }: {
  label: string;
  hint?: ReactNode;
  tri: Tri;
  onChange: (choice: "unset" | "true" | "false") => void;
  inherited?: string;
}) {
  const id = useId();
  return (
    <Field label={label} hint={hint} htmlFor={id}>
      <NativeSelect id={id} className="w-full" value={tri.state}
        onChange={(e) => { if (e.target.value !== "other") onChange(e.target.value as "unset" | "true" | "false"); }}>
        <option value="unset">{inherited ? `Not set (inherited: ${inherited})` : "Not set"}</option>
        <option value="true">Yes</option>
        <option value="false">No</option>
        {tri.state === "other" && <option value="other">{tri.raw}</option>}
      </NativeSelect>
    </Field>
  );
}

/**
 * A secret. The server never sends one: a saved secret arrives as `****`, which is kept unless a new value is typed.
 * `hadSaved` says there is one in the file, so that "keep it" can be offered after the field was emptied.
 */
export function SecretField({ label, hint, value, onChange, hadSaved, bare }: {
  label: string;
  hint?: ReactNode;
  value: string;
  onChange: (value: string) => void;
  hadSaved: boolean;
  /** Without its own label, inside a table row. */
  bare?: boolean;
}) {
  const id = useId();
  const [shown, setShown] = useState(false);
  const saved = value === MASK;
  const control = saved ? (
    <div className="flex h-11 items-center gap-2 rounded-[10px] border border-gray-300 bg-gray-50 px-4 dark:border-gray-700 dark:bg-white/3">
      <span className="font-mono text-sm tracking-widest text-gray-500 dark:text-gray-400" aria-hidden>••••••••</span>
      <span className="text-xs text-gray-500 dark:text-gray-400">saved</span>
      <Button variant="ghost" size="xs" className="ms-auto" onClick={() => onChange("")} aria-label={`Change ${label}`}>Change</Button>
    </div>
  ) : (
    <div className="flex items-center gap-2">
      <div className="relative min-w-0 flex-1">
        <Input id={id} aria-label={bare ? label : undefined} type={shown ? "text" : "password"} autoComplete="new-password" spellCheck={false} className="pe-12"
          placeholder={hadSaved ? "Type the new value (empty removes it)" : undefined} value={value} onChange={(e) => onChange(e.target.value)} />
        <button type="button" aria-label={shown ? "Hide" : "Show"} onClick={() => setShown(!shown)}
          className="absolute end-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200">
          {shown ? <EyeCloseIcon className="size-5" /> : <EyeIcon className="size-5" />}
        </button>
      </div>
      {hadSaved && <Button variant="outline" size="xs" onClick={() => onChange(MASK)}>Keep the saved one</Button>}
    </div>
  );
  return bare ? control : <Field label={label} hint={hint} htmlFor={id}>{control}</Field>;
}

/**
 * Name/value rows (headers, variables, attachments). A value whose name looks like a secret is treated as one.
 * `valueLabel` also names the inputs for people who use a screen reader.
 */
export function TableEditor({ rows, onChange, nameLabel, valueLabel, namePlaceholder, valuePlaceholder, addLabel, emptyText, mono }: {
  rows: Row[];
  onChange: (rows: Row[]) => void;
  nameLabel: string;
  valueLabel: string;
  namePlaceholder?: string;
  valuePlaceholder?: string;
  addLabel: string;
  emptyText: string;
  mono?: boolean;
}) {
  const set = (id: string, change: Partial<Row>) => onChange(rows.map((r) => (r.id === id ? { ...r, ...change } : r)));
  const repeated = repeatedNames(rows);
  return (
    <div className="flex flex-col gap-2">
      {rows.length === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">{emptyText}</p>}
      {rows.map((r, i) => {
        const secret = r.saved || isSecretName(r.name);
        return (
          <div key={r.id} className="grid items-start gap-2 sm:grid-cols-[minmax(0,2fr)_minmax(0,3fr)_auto]">
            <Input aria-label={`${nameLabel} ${i + 1}`} className={mono ? "font-mono text-xs" : undefined} spellCheck={false} autoComplete="off"
              placeholder={namePlaceholder} value={r.name} onChange={(e) => set(r.id, { name: e.target.value })} />
            {secret ? (
              <SecretField bare label={`${valueLabel} ${i + 1}`} value={r.value} hadSaved={!!r.saved}
                onChange={(value) => set(r.id, { value, removed: false })} />
            ) : (
              <Input aria-label={`${valueLabel} ${i + 1}`} className={mono ? "font-mono text-xs" : undefined} spellCheck={false} autoComplete="off"
                placeholder={r.removed ? "(removes the inherited one)" : valuePlaceholder} value={r.value}
                onChange={(e) => set(r.id, { value: e.target.value, removed: false })} />
            )}
            <Button variant="ghost" size="icon" aria-label={`Remove ${nameLabel.toLowerCase()} ${i + 1}`} onClick={() => onChange(rows.filter((x) => x.id !== r.id))}>
              <TrashBinIcon />
            </Button>
          </div>
        );
      })}
      {repeated.length > 0 && (
        <p role="alert" className="text-xs text-amber-600 dark:text-amber-400">
          {repeated.join(", ")} appear{repeated.length === 1 ? "s" : ""} more than once: only the last one is kept.
        </p>
      )}
      <div>
        <Button variant="outline" size="sm" onClick={() => onChange([...rows, { id: newRowId(), name: "", value: "" }])}><PlusIcon /> {addLabel}</Button>
      </div>
    </div>
  );
}
