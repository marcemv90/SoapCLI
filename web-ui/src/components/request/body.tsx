import { useDeferredValue, useEffect, useMemo, useRef, useState, type DragEvent, type ReactNode } from "react";
import { CodeEditor } from "@/components/common/CodeEditor";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { AlertIcon, CheckIcon, PaperClipIcon, TrashBinIcon } from "@/icons";
import { findCids } from "@/lib/cid";
import { formatBytes } from "@/lib/format";
import { findPlaceholders } from "@/lib/placeholders";
import { detectSoapVersion } from "@/lib/soap";
import { checkAttachment } from "@/lib/transports/server";
import { newId, type AttachmentInput } from "@/lib/types";
import { clean, prettyPrint } from "@/lib/xml";
import { useWorkspace } from "@/state/workspace";

const Code = ({ children }: { children: ReactNode }) => (
  <code className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-700 dark:bg-white/10 dark:text-gray-300">{children}</code>
);

function Section({ title, count, hint, children }: { title: string; count: number; hint: ReactNode; children?: ReactNode }) {
  return (
    <section aria-label={title} className="rounded-xl border border-gray-200 dark:border-gray-800">
      <div className="flex items-center gap-2 border-b border-gray-200 px-4 py-3 dark:border-gray-800">
        <h3 className="text-sm font-semibold text-gray-800 dark:text-white/90">{title}</h3>
        {count > 0 && <Badge variant="light" color="primary" size="sm" className="px-1.5">{count}</Badge>}
      </div>
      <div className="flex flex-col gap-3 p-4">
        {count === 0 && <p className="text-sm text-gray-500 dark:text-gray-400">{hint}</p>}
        {children}
      </div>
    </section>
  );
}

function VariablesSection({ body }: { body: string }) {
  const { draft, patch } = useWorkspace();
  const placeholders = useMemo(() => findPlaceholders(body), [body]);
  const set = (name: string, value: string) => {
    const args = { ...draft.args };
    if (value === "") delete args[name];
    else args[name] = value;
    patch({ args });
  };
  return (
    <Section
      title="Variables"
      count={placeholders.length}
      hint={<>Write <Code>___NAME:default___</Code> anywhere in the request and it shows up here, so you can change the value without editing the XML.</>}
    >
      {placeholders.length > 0 && (
        <p className="text-xs text-gray-500 dark:text-gray-400">Leave a value empty to use the default written in the request.</p>
      )}
      {placeholders.map((p) => (
        <div key={p.name} className="grid items-center gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,2fr)_auto]">
          <code className="truncate text-sm font-medium text-gray-800 dark:text-white/90" title={p.name}>{p.name}</code>
          <Input aria-label={`Value for ${p.name}`} placeholder={p.defaultValue || "(empty)"} value={draft.args[p.name] ?? ""}
            onChange={(e) => set(p.name, e.target.value)} />
          <Badge variant="light" color="gray" size="sm" className="justify-self-start">
            {draft.args[p.name] !== undefined ? "custom" : "default"}
          </Badge>
        </div>
      ))}
    </Section>
  );
}

type PathCheck =
  | { state: "idle" }
  | { state: "checking" }
  | { state: "ok"; name: string; size: number; resolved: string }
  | { state: "error"; message: string };

/** Asks the server, a moment after the typing stops, whether it can read the file at `path`. */
function usePathCheck(path: string): PathCheck {
  const [result, setResult] = useState<{ path: string; check: PathCheck }>({ path: "", check: { state: "idle" } });
  const wanted = path.trim();
  useEffect(() => {
    if (!wanted) return;
    const ctl = new AbortController();
    const timer = setTimeout(() => {
      checkAttachment(wanted, ctl.signal).then(
        (found) => setResult({ path: wanted, check: { state: "ok", name: found.name, size: found.size, resolved: found.resolved } }),
        (e: unknown) => {
          if (!ctl.signal.aborted) setResult({ path: wanted, check: { state: "error", message: e instanceof Error ? e.message : String(e) } });
        },
      );
    }, 400);
    return () => {
      clearTimeout(timer);
      ctl.abort();
    };
  }, [wanted]);
  if (!wanted) return { state: "idle" };
  return result.path === wanted ? result.check : { state: "checking" };
}

function PathStatus({ check, empty, cid }: { check: PathCheck; empty: boolean; cid: string }) {
  if (empty) {
    return <p className="text-xs text-amber-600 dark:text-amber-400">No file yet: <code>cid:{cid}</code> is sent as written.</p>;
  }
  if (check.state === "checking") {
    return <p className="flex items-center gap-1.5 text-xs text-gray-500 dark:text-gray-400"><Spinner className="size-3" /> Looking for the file…</p>;
  }
  if (check.state === "ok") {
    return (
      <p className="flex items-center gap-1.5 text-xs text-green-600 dark:text-green-400" title={check.resolved}>
        <CheckIcon className="size-3.5" /> {check.name} · {formatBytes(check.size)}
      </p>
    );
  }
  if (check.state === "error") {
    return <p className="flex items-start gap-1.5 text-xs text-red-600 dark:text-red-400" role="alert"><AlertIcon className="mt-0.5 size-3.5 shrink-0" /> {check.message}</p>;
  }
  return null;
}

/** One `cid:` of the request and the file for it: a path on the server (saved), or a file from this computer (not saved). */
function AttachmentRow({ cid, entry, onPath, onFile, onRemove }: {
  cid: string;
  entry: AttachmentInput | undefined;
  onPath: (path: string) => void;
  onFile: (file: File) => void;
  onRemove: () => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const path = entry?.kind === "path" ? entry.path : "";
  const check = usePathCheck(path);
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const dropped = e.dataTransfer.files[0];
    if (dropped) onFile(dropped);
  };
  return (
    <div
      onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
      className={`flex flex-col gap-2 rounded-lg border px-3 py-2.5 transition-colors ${
        dragging ? "border-brand-400 bg-brand-50 dark:bg-brand-500/10" : "border-transparent bg-gray-50 dark:bg-white/3"}`}
    >
      <code className="truncate text-sm font-medium text-gray-800 dark:text-white/90" title={`cid:${cid}`}>cid:{cid}</code>
      {entry?.kind === "file" ? (
        <div className="flex min-w-0 items-center gap-2">
          <span className="truncate text-xs text-gray-600 dark:text-gray-400" title={entry.filename}>{entry.filename}</span>
          <Badge variant="light" color="gray" size="sm">{formatBytes(entry.size)}</Badge>
          <Badge variant="light" color="info" size="sm" title="Kept in memory only: it is not saved with the request">this computer, not saved</Badge>
          <Button variant="ghost" size="icon-xs" className="ms-auto" aria-label={`Remove the file for cid:${cid}`} onClick={onRemove}>
            <TrashBinIcon className="size-4" />
          </Button>
        </div>
      ) : (
        <>
          <div className="flex items-center gap-2">
            <Input
              aria-label={`Path on the server of the file for cid:${cid}`}
              aria-invalid={check.state === "error" || undefined}
              className="h-9 font-mono text-xs"
              placeholder="Path on the server, e.g. invoices/invoice.pdf"
              spellCheck={false}
              autoComplete="off"
              value={path}
              onChange={(e) => onPath(e.target.value)}
            />
            <Button variant="outline" size="xs" className="shrink-0" aria-label={`Choose a file from this computer for cid:${cid}`}
              title="Use a file from this computer instead (not saved with the request)" onClick={() => input.current?.click()}>
              <PaperClipIcon /> Choose file
            </Button>
            <input ref={input} type="file" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ""; }} />
          </div>
          <PathStatus check={check} empty={path.trim() === ""} cid={cid} />
        </>
      )}
    </div>
  );
}

function AttachmentsSection({ body }: { body: string }) {
  const { draft, patch, update, server } = useWorkspace();
  // Inline mode replaces CDATA text too; MTOM does not. Same rule as the server.
  const cids = useMemo(() => findCids(body, { cdata: !draft.mtom }), [body, draft.mtom]);
  const byCid = useMemo(() => new Map(draft.attachments.map((a) => [a.cid, a])), [draft.attachments]);
  const unused = draft.attachments.filter((a) => !cids.includes(a.cid));
  const folder = server.info?.attachmentsDir;

  const withoutCid = (d: typeof draft, cid: string) => d.attachments.filter((a) => a.cid !== cid);
  const setPath = (cid: string, path: string) =>
    update((d) => ({
      ...d,
      // Emptying the field is removing the file.
      attachments: path.trim() === "" ? withoutCid(d, cid) : [...withoutCid(d, cid), { kind: "path" as const, id: newId(), cid, path }],
    }));
  const chooseFile = async (cid: string, file: File) => {
    const data = new Uint8Array(await file.arrayBuffer());
    update((d) => ({
      ...d,
      attachments: [...withoutCid(d, cid), { kind: "file" as const, id: newId(), cid, filename: file.name, size: data.length, data }],
    }));
  };
  const remove = (cid: string) => update((d) => ({ ...d, attachments: withoutCid(d, cid) }));

  return (
    <Section
      title="Attachments"
      count={cids.length}
      hint={<>
        Write <Code>cid:NAME</Code> as the text of an element, for example <Code>{"<value>cid:report</value>"}</Code>, and the file to send
        shows up here. A <Code>cid:</Code> inside an attribute (<Code>payloadId=&quot;cid:message&quot;</Code>) is not an attachment.
      </>}
    >
      {cids.length > 0 && (
        <p className="text-xs text-gray-500 dark:text-gray-400">
          Type the <strong className="font-medium">path of each file on the server</strong>: it is saved with the request, and the server loads the
          files when you send it{folder ? <>, from <Code>{folder}</Code> (nothing outside that folder can be attached)</> : ""}. Or choose a file from this
          computer, which is kept in memory only.
        </p>
      )}
      {cids.map((cid) => (
        <AttachmentRow key={cid} cid={cid} entry={byCid.get(cid)} onPath={(p) => setPath(cid, p)} onFile={(f) => void chooseFile(cid, f)} onRemove={() => remove(cid)} />
      ))}
      {unused.length > 0 && (
        <div className="flex flex-col gap-2">
          <p className="text-xs text-gray-500 dark:text-gray-400">No longer referenced by the request, so not sent:</p>
          {unused.map((a) => (
            <div key={a.id} className="flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
              <code className="truncate">cid:{a.cid}</code>
              <span className="truncate" title={a.kind === "path" ? a.path : a.filename}>{a.kind === "path" ? a.path : a.filename}</span>
              <Button variant="ghost" size="icon-xs" className="ms-auto" aria-label={`Remove the file for cid:${a.cid}`} onClick={() => remove(a.cid)}>
                <TrashBinIcon className="size-4" />
              </Button>
            </div>
          ))}
        </div>
      )}
      <label className="flex items-start gap-3 border-t border-gray-200 pt-3 dark:border-gray-800">
        <Switch checked={draft.mtom} onCheckedChange={(mtom) => patch({ mtom })} aria-label="Send as MTOM" />
        <span>
          <span className="block text-sm font-medium text-gray-800 dark:text-white/90">Send as MTOM/XOP</span>
          <span className="block text-xs text-gray-500 dark:text-gray-400">
            By default a file is inlined as Base64 where its <Code>cid:</Code> is written. MTOM sends it as a binary part of a
            multipart/related message instead.
          </span>
        </span>
      </label>
    </Section>
  );
}

export function BodyTab() {
  const { draft, patch } = useWorkspace();
  // Typing never waits for the scans below.
  const body = useDeferredValue(draft.body);
  const detected = useMemo(() => detectSoapVersion(clean(body)), [body]);
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          {detected
            ? <Badge variant="light" color="success" size="sm">Detected SOAP {detected}</Badge>
            : <Badge variant="light" color="gray" size="sm">SOAP version not detected</Badge>}
          <div className="flex gap-2">
            <Button variant="outline" size="xs" onClick={() => patch({ body: prettyPrint(draft.body) })}>Format</Button>
            <Button variant="outline" size="xs" onClick={() => patch({ body: "" })}>Clear</Button>
          </div>
        </div>
        <CodeEditor value={draft.body} onChange={(next) => patch({ body: next })} placeholder="Paste your SOAP envelope here" minHeight="360px" />
      </div>
      <VariablesSection body={body} />
      <AttachmentsSection body={body} />
    </div>
  );
}
