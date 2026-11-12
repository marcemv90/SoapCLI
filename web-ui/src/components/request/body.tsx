import { useDeferredValue, useMemo, useRef, useState, type DragEvent, type ReactNode } from "react";
import { CodeEditor } from "@/components/common/CodeEditor";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { PaperClipIcon, TrashBinIcon } from "@/icons";
import { findCids } from "@/lib/cid";
import { formatBytes } from "@/lib/format";
import { findPlaceholders } from "@/lib/placeholders";
import { detectSoapVersion } from "@/lib/soap";
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

/** One `cid:` of the request and the file chosen for it. */
function AttachmentRow({ cid, file, onChoose, onRemove }: {
  cid: string;
  file: AttachmentInput | undefined;
  onChoose: (file: File) => void;
  onRemove: () => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const dropped = e.dataTransfer.files[0];
    if (dropped) onChoose(dropped);
  };
  return (
    <div
      onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
      className={`flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border px-3 py-2 transition-colors ${
        dragging ? "border-brand-400 bg-brand-50 dark:bg-brand-500/10" : "border-transparent bg-gray-50 dark:bg-white/3"}`}
    >
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <code className="truncate text-sm font-medium text-gray-800 dark:text-white/90" title={`cid:${cid}`}>cid:{cid}</code>
        {file ? (
          <span className="flex min-w-0 items-center gap-2">
            <span className="truncate text-xs text-gray-600 dark:text-gray-400" title={file.filename}>{file.filename}</span>
            <Badge variant="light" color="gray" size="sm">{formatBytes(file.size)}</Badge>
          </span>
        ) : (
          <span><Badge variant="light" color="warning" size="sm">no file chosen</Badge></span>
        )}
      </div>
      <span className="flex items-center gap-1">
        <Button variant="outline" size="xs" aria-label={`${file ? "Replace" : "Choose"} the file for cid:${cid}`} onClick={() => input.current?.click()}>
          <PaperClipIcon /> {file ? "Replace" : "Choose file"}
        </Button>
        {file && (
          <Button variant="ghost" size="icon-xs" aria-label={`Remove the file for cid:${cid}`} onClick={onRemove}><TrashBinIcon className="size-4" /></Button>
        )}
        <input ref={input} type="file" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) onChoose(f); e.target.value = ""; }} />
      </span>
    </div>
  );
}

function AttachmentsSection({ body }: { body: string }) {
  const { draft, patch, update } = useWorkspace();
  // Inline mode replaces CDATA text too; MTOM does not. Same rule as the server.
  const cids = useMemo(() => findCids(body, { cdata: !draft.mtom }), [body, draft.mtom]);
  const byCid = useMemo(() => new Map(draft.attachments.map((a) => [a.cid, a])), [draft.attachments]);
  const unused = draft.attachments.filter((a) => !cids.includes(a.cid));

  const choose = async (cid: string, file: File) => {
    const data = new Uint8Array(await file.arrayBuffer());
    update((d) => ({
      ...d,
      attachments: [...d.attachments.filter((a) => a.cid !== cid), { id: newId(), cid, filename: file.name, size: data.length, data }],
    }));
  };
  const remove = (cid: string) => update((d) => ({ ...d, attachments: d.attachments.filter((a) => a.cid !== cid) }));

  return (
    <Section
      title="Attachments"
      count={cids.length}
      hint={<>
        Write <Code>cid:NAME</Code> as the text of an element, for example <Code>{"<value>cid:report</value>"}</Code>, and the file to send
        shows up here. A <Code>cid:</Code> inside an attribute (<Code>payloadId=&quot;cid:message&quot;</Code>) is not an attachment.
      </>}
    >
      {cids.map((cid) => (
        <AttachmentRow key={cid} cid={cid} file={byCid.get(cid)} onChoose={(f) => void choose(cid, f)} onRemove={() => remove(cid)} />
      ))}
      {unused.length > 0 && (
        <div className="flex flex-col gap-2">
          <p className="text-xs text-gray-500 dark:text-gray-400">No longer referenced by the request, so not sent:</p>
          {unused.map((a) => (
            <div key={a.id} className="flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
              <code className="truncate">cid:{a.cid}</code>
              <span className="truncate" title={a.filename}>{a.filename}</span>
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
            multipart/related message instead. Files stay in memory and are not saved.
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
