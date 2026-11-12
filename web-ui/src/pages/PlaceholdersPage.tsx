import { useEffect, useId, useState } from "react";
import { EmptyState } from "@/components/common/controls";
import { Field, FormSection } from "@/components/profiles/form-controls";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Textarea } from "@/components/ui/textarea";
import { AlertIcon, ArrowDownIcon, ArrowUpIcon, ListIcon, PlusIcon, TrashBinIcon } from "@/icons";
import {
  PLACEHOLDER_NAME, duplicateValues, newOptionRow, parseOptionLines, toOptions, type OptionRow,
} from "@/lib/placeholder-options";
import { ServerError, deletePlaceholder, savePlaceholder, type PlaceholderEntry } from "@/lib/transports/server";
import { useWorkspace } from "@/state/workspace";
import { cn } from "@/utils";

function PlaceholderForm({ mode, entry, name: initialName, revision, onSaved, onCancel, onReload }: {
  mode: "create" | "edit";
  entry?: PlaceholderEntry;
  name: string;
  revision: string;
  onSaved: (name: string) => void | Promise<void>;
  onCancel: () => void;
  onReload: () => void;
}) {
  const [name, setName] = useState(initialName);
  const [description, setDescription] = useState(entry?.description ?? "");
  const [rows, setRows] = useState<OptionRow[]>(() => (entry?.options.length ? entry.options.map((o) => newOptionRow(o.value, o.label ?? "")) : [newOptionRow()]));
  const [pasting, setPasting] = useState(false);
  const [pasted, setPasted] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<{ message: string; stale: boolean } | null>(null);
  const nameId = useId();
  const descriptionId = useId();
  const pasteId = useId();

  const filled = rows.filter((r) => r.value !== "");
  const dup = duplicateValues(rows);
  const nameOk = mode === "edit" || PLACEHOLDER_NAME.test(name);
  const canSave = !saving && nameOk && filled.length > 0 && dup.length === 0;

  const move = (index: number, by: -1 | 1) => {
    const next = [...rows];
    const target = index + by;
    if (target < 0 || target >= next.length) return;
    [next[index], next[target]] = [next[target], next[index]];
    setRows(next);
  };
  const set = (id: string, change: Partial<OptionRow>) => setRows(rows.map((r) => (r.id === id ? { ...r, ...change } : r)));

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await savePlaceholder({ name: name.trim(), revision, create: mode === "create", description: description.trim(), options: toOptions(rows) });
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      setError({ message, stale: e instanceof ServerError && e.category === "conflict" && /changed since you opened it/.test(message) });
      setSaving(false);
      return;
    }
    await onSaved(name.trim());
    setSaving(false);
  };

  return (
    <form className="flex flex-col gap-4" aria-label={mode === "create" ? "New placeholder" : `Edit ${initialName}`}
      onSubmit={(e) => { e.preventDefault(); if (canSave) void save(); }}>
      {error && (
        <Alert variant="destructive">
          <AlertIcon />
          <AlertTitle>The options were not saved</AlertTitle>
          <AlertDescription>
            {error.message}
            {error.stale && <span className="mt-2 block"><Button type="button" variant="outline" size="xs" onClick={onReload}>Reload the file (gives up my edits)</Button></span>}
          </AlertDescription>
        </Alert>
      )}
      <FormSection title="Placeholder">
        <div className="grid gap-4 sm:grid-cols-2">
          {mode === "create" ? (
            <Field label="Name" htmlFor={nameId}
              hint={name && !PLACEHOLDER_NAME.test(name) ? "Letters, digits, _ . - ; it does not start with a digit" : "As in ___NAME:default___ in a request"}>
              <Input id={nameId} className="font-mono text-xs" spellCheck={false} autoComplete="off" value={name} onChange={(e) => setName(e.target.value)} />
            </Field>
          ) : (
            <div><span className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">Name</span><span className="font-mono text-sm text-gray-800 dark:text-white/90">{initialName}</span></div>
          )}
          <Field label="Description" htmlFor={descriptionId} hint="Optional. For whoever picks a value.">
            <Input id={descriptionId} value={description} onChange={(e) => setDescription(e.target.value)} />
          </Field>
        </div>
      </FormSection>

      <FormSection title="Options" description="What the dropdown offers, in this order. The label is optional: it is what is shown instead of the value.">
        <div className="flex flex-col gap-2">
          {rows.map((r, i) => (
            <div key={r.id} className="grid items-center gap-2 sm:grid-cols-[minmax(0,3fr)_minmax(0,3fr)_auto]">
              <Input aria-label={`Option ${i + 1} value`} className="font-mono text-xs" placeholder="value" spellCheck={false} autoComplete="off"
                aria-invalid={dup.includes(r.value) || undefined} value={r.value} onChange={(e) => set(r.id, { value: e.target.value })} />
              <Input aria-label={`Option ${i + 1} label`} placeholder="label (optional)" value={r.label} onChange={(e) => set(r.id, { label: e.target.value })} />
              <span className="flex">
                <Button type="button" variant="ghost" size="icon-sm" aria-label={`Move option ${i + 1} up`} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUpIcon /></Button>
                <Button type="button" variant="ghost" size="icon-sm" aria-label={`Move option ${i + 1} down`} disabled={i === rows.length - 1} onClick={() => move(i, 1)}><ArrowDownIcon /></Button>
                <Button type="button" variant="ghost" size="icon-sm" aria-label={`Remove option ${i + 1}`} onClick={() => setRows(rows.length > 1 ? rows.filter((x) => x.id !== r.id) : [newOptionRow()])}><TrashBinIcon /></Button>
              </span>
            </div>
          ))}
          {dup.length > 0 && <p role="alert" className="text-xs text-red-600 dark:text-red-400">Listed twice: {dup.join(", ")}.</p>}
          <div className="flex flex-wrap gap-2">
            <Button type="button" variant="outline" size="sm" onClick={() => setRows([...rows, newOptionRow()])}><PlusIcon /> Add option</Button>
            <Button type="button" variant="ghost" size="sm" onClick={() => setPasting(!pasting)}>{pasting ? "Hide" : "Paste a list"}</Button>
          </div>
          {pasting && (
            <div className="flex flex-col gap-2">
              <label htmlFor={pasteId} className="text-xs text-gray-500 dark:text-gray-400">One option per line: <code>value</code> or <code>value | label</code>.</label>
              <Textarea id={pasteId} rows={5} className="font-mono text-xs" value={pasted} onChange={(e) => setPasted(e.target.value)} />
              <div>
                <Button type="button" size="sm" disabled={parseOptionLines(pasted).length === 0}
                  onClick={() => { setRows([...rows.filter((r) => r.value !== "" || r.label !== ""), ...parseOptionLines(pasted)]); setPasted(""); setPasting(false); }}>
                  Add these options
                </Button>
              </div>
            </div>
          )}
        </div>
      </FormSection>

      <div className="sticky bottom-0 -mx-4 -mb-4 flex flex-wrap items-center gap-3 border-t border-gray-200 bg-white/95 px-4 py-3 backdrop-blur sm:-mx-5 sm:-mb-5 sm:px-5 dark:border-gray-800 dark:bg-gray-900/95">
        <span className="text-xs text-gray-500 dark:text-gray-400">{filled.length} option{filled.length === 1 ? "" : "s"}</span>
        <div className="ms-auto flex gap-2">
          <Button type="button" variant="outline" onClick={onCancel} disabled={saving}>Cancel</Button>
          <Button type="submit" disabled={!canSave}>{saving && <Spinner />} {mode === "create" ? "Add placeholder" : "Save changes"}</Button>
        </div>
      </div>
    </form>
  );
}

type Mode = { kind: "view" } | { kind: "create"; name: string } | { kind: "edit"; name: string };

export function PlaceholdersPage() {
  const { server, refreshServer, placeholderIntent, setPlaceholderIntent } = useWorkspace();
  const result = server.placeholders;
  const entries = result?.placeholders ?? [];
  const [chosen, setChosen] = useState<string | null>(null);
  // Coming from a request's variable: straight to giving it options.
  const [mode, setMode] = useState<Mode>(() => {
    if (!placeholderIntent) return { kind: "view" };
    return entries.some((e) => e.name === placeholderIntent.name)
      ? { kind: "edit", name: placeholderIntent.name }
      : { kind: "create", name: placeholderIntent.name };
  });
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    if (placeholderIntent) setPlaceholderIntent(null);
  }, [placeholderIntent, setPlaceholderIntent]);

  const editable = result?.editable === true;
  const editing = mode.kind !== "view";
  const selected = entries.find((e) => e.name === (mode.kind === "edit" ? mode.name : chosen)) ?? (mode.kind === "view" ? entries.find((e) => e.name === chosen) ?? entries[0] : undefined);
  const editedEntry = mode.kind === "edit" ? entries.find((e) => e.name === mode.name) : undefined;

  const saved = async (name: string) => {
    await refreshServer(); // show the list as it is now, not for a moment as it was
    setChosen(name);
    setMode({ kind: "view" });
    setNotice({ ok: true, text: `Saved the options of “${name}”.` });
  };
  const remove = async (name: string) => {
    setDeleting(true);
    setNotice(null);
    try {
      await deletePlaceholder(name, result?.revision ?? "");
      await refreshServer();
      setChosen(null);
      setNotice({ ok: true, text: `Removed the options of “${name}”.` });
    } catch (e) {
      setNotice({ ok: false, text: e instanceof Error ? e.message : String(e) });
    } finally {
      setDeleting(false);
    }
  };

  if (server.loaded && !server.info) {
    return <Card><EmptyState icon={<ListIcon />} title="No SoapCLI server">Placeholder options live on the SoapCLI server, and this page was not served by one.</EmptyState></Card>;
  }

  return (
    <div className="flex flex-col gap-4 md:gap-6">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm text-gray-500 dark:text-gray-400">
        <span>Placeholders file:</span>
        <code className="rounded bg-gray-100 px-1.5 py-0.5 break-all text-gray-700 dark:bg-white/10 dark:text-gray-300">{result?.file ?? server.info?.placeholdersFile ?? "…"}</code>
        <Button variant="outline" size="xs" onClick={refreshServer} disabled={editing}>Reload</Button>
        {editable && <Button size="xs" onClick={() => { setNotice(null); setMode({ kind: "create", name: "" }); }} disabled={editing}><PlusIcon /> New placeholder</Button>}
        <span className="text-xs">
          {editable ? "The options a request's variable offers in a dropdown. Changes are written to this file; the rest of it, comments included, is left as it is."
            : result?.readOnlyReason ?? "The options a request's variable offers in a dropdown."}
        </span>
      </div>

      {notice && <Alert variant={notice.ok ? "success" : "destructive"}>{!notice.ok && <AlertIcon />}<AlertDescription>{notice.text}</AlertDescription></Alert>}
      {result?.error && <Alert variant="destructive"><AlertIcon /><AlertTitle>The placeholders file cannot be read</AlertTitle><AlertDescription>{result.error}</AlertDescription></Alert>}

      {result && !result.found && mode.kind !== "create" && (
        <Card>
          <EmptyState icon={<ListIcon />} title="No placeholders file"
            action={editable ? <Button size="sm" onClick={() => setMode({ kind: "create", name: "" })}><PlusIcon /> Add the first placeholder</Button> : undefined}>
            A placeholder is a <code>___NAME:default___</code> in a request. Give it options and its variable becomes a dropdown.
            {editable ? " Adding one creates the file." : " Create the file above (or point the server to another with --placeholders-file)."}
          </EmptyState>
        </Card>
      )}

      {(entries.length > 0 || mode.kind === "create") && (
        <div className="grid grid-cols-1 items-start gap-4 md:gap-6 xl:grid-cols-[18rem_minmax(0,1fr)]">
          <Card className="gap-0 overflow-hidden p-0 sm:p-0">
            <ul aria-label="Placeholders" className="divide-y divide-gray-200 dark:divide-gray-800">
              {entries.map((e) => {
                const active = mode.kind !== "create" && e.name === selected?.name;
                return (
                  <li key={e.name}>
                    <button type="button" disabled={editing} aria-current={active ? "true" : undefined} title={editing ? "Finish or cancel the edit first" : undefined}
                      onClick={() => { setChosen(e.name); setNotice(null); setArmed(false); }}
                      className={cn("flex w-full items-center gap-2 px-4 py-3 text-start text-sm transition-colors outline-hidden focus-visible:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60 dark:focus-visible:bg-white/5",
                        active ? "bg-brand-50 dark:bg-brand-500/10" : "hover:bg-gray-50 dark:hover:bg-white/5")}>
                      <span className="truncate font-mono text-xs font-medium text-gray-800 dark:text-white/90">{e.name}</span>
                      {e.error ? <Badge variant="light" color="error" size="sm" className="ms-auto">error</Badge>
                        : <Badge variant="light" color="gray" size="sm" className="ms-auto">{e.options.length}</Badge>}
                    </button>
                  </li>
                );
              })}
              {mode.kind === "create" && <li className="bg-brand-50 px-4 py-3 text-sm font-medium text-gray-800 dark:bg-brand-500/10 dark:text-white/90">New placeholder</li>}
            </ul>
          </Card>

          <Card className="min-w-0 gap-0 overflow-hidden p-0 sm:p-0">
            {mode.kind === "create" ? (
              <>
                <div className="border-b border-gray-200 px-4 py-4 sm:px-5 dark:border-gray-800"><h2 className="text-base font-semibold text-gray-800 dark:text-white/90">New placeholder</h2></div>
                <div className="p-4 sm:p-5">
                  <PlaceholderForm key={`create-${mode.name}`} mode="create" name={mode.name} revision={result?.revision ?? "missing"}
                    onSaved={saved} onCancel={() => setMode({ kind: "view" })} onReload={() => { refreshServer(); setMode({ kind: "view" }); }} />
                </div>
              </>
            ) : mode.kind === "edit" ? (
              <>
                <div className="border-b border-gray-200 px-4 py-4 sm:px-5 dark:border-gray-800"><h2 className="font-mono text-base font-semibold text-gray-800 dark:text-white/90">{mode.name}</h2></div>
                <div className="p-4 sm:p-5">
                  <PlaceholderForm key={`edit-${mode.name}-${result?.revision}`} mode="edit" name={mode.name} entry={editedEntry} revision={result?.revision ?? "missing"}
                    onSaved={saved} onCancel={() => setMode({ kind: "view" })} onReload={() => { refreshServer(); setMode({ kind: "view" }); }} />
                </div>
              </>
            ) : selected ? (
              <>
                <div className="flex flex-wrap items-center gap-3 border-b border-gray-200 px-4 py-4 sm:px-5 dark:border-gray-800">
                  <h2 className="font-mono text-base font-semibold text-gray-800 dark:text-white/90">{selected.name}</h2>
                  {editable && (
                    <div className="ms-auto flex gap-2">
                      <Button variant="outline" size="sm" onClick={() => { setNotice(null); setMode({ kind: "edit", name: selected.name }); }}>Edit</Button>
                      <Button variant={armed ? "destructive" : "outline"} size="sm" disabled={deleting}
                        onClick={() => { if (armed) { setArmed(false); void remove(selected.name); } else { setArmed(true); setTimeout(() => setArmed(false), 3000); } }}>
                        {deleting && <Spinner />} {armed ? "Click again to remove" : "Remove"}
                      </Button>
                    </div>
                  )}
                </div>
                <div className="flex flex-col gap-3 p-4 sm:p-5">
                  {selected.error && <Alert variant="destructive"><AlertIcon /><AlertTitle>These options cannot be used</AlertTitle><AlertDescription>{selected.error}</AlertDescription></Alert>}
                  {selected.description && <p className="text-sm text-gray-600 dark:text-gray-400">{selected.description}</p>}
                  {selected.options.length > 0 && (
                    <div className="overflow-x-auto rounded-xl border border-gray-200 dark:border-gray-800">
                      <table className="min-w-full text-sm">
                        <thead className="bg-gray-50 text-start text-xs text-gray-500 dark:bg-white/3 dark:text-gray-400">
                          <tr><th className="px-4 py-2 text-start font-medium">Value</th><th className="px-4 py-2 text-start font-medium">Shown as</th></tr>
                        </thead>
                        <tbody className="divide-y divide-gray-200 dark:divide-gray-800">
                          {selected.options.map((o) => (
                            <tr key={o.value}>
                              <td className="px-4 py-2.5 font-mono text-xs break-all text-gray-800 dark:text-white/90">{o.value}</td>
                              <td className="px-4 py-2.5 text-gray-600 dark:text-gray-400">{o.label ?? <span className="text-gray-400">the value</span>}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  )}
                </div>
              </>
            ) : null}
          </Card>
        </div>
      )}
    </div>
  );
}
