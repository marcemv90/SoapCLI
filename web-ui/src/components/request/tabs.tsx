import { useMemo, useRef, useState, type DragEvent } from "react";
import { CodeEditor } from "@/components/common/CodeEditor";
import { FieldLabel, Segmented } from "@/components/common/controls";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { EyeCloseIcon, EyeIcon, PaperClipIcon, PlusIcon, TrashBinIcon, UploadIcon } from "@/icons";
import { formatBytes } from "@/lib/format";
import { findPlaceholders } from "@/lib/placeholders";
import { isForbiddenHeader } from "@/lib/request";
import { SAMPLE_BODY } from "@/lib/sample";
import { detectSoapVersion } from "@/lib/soap";
import { newId, type AuthKind } from "@/lib/types";
import { clean, prettyPrint } from "@/lib/xml";
import { useWorkspace } from "@/state/workspace";

const COMMON_HEADERS = ["SOAPAction", "Accept", "Accept-Language", "Content-Type", "X-Correlation-ID", "X-Request-ID", "X-Api-Key", "X-Forwarded-For"];

export function BodyTab() {
  const { draft, patch } = useWorkspace();
  const detected = useMemo(() => detectSoapVersion(clean(draft.body)), [draft.body]);
  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        {detected
          ? <Badge variant="light" color="success" size="sm">Detected SOAP {detected}</Badge>
          : <Badge variant="light" color="gray" size="sm">SOAP version not detected</Badge>}
        <div className="flex gap-2">
          <Button variant="outline" size="xs" onClick={() => patch({ body: prettyPrint(draft.body) })}>Format</Button>
          <Button variant="outline" size="xs" onClick={() => patch({ body: SAMPLE_BODY })}>Sample</Button>
          <Button variant="outline" size="xs" onClick={() => patch({ body: "" })}>Clear</Button>
        </div>
      </div>
      <CodeEditor value={draft.body} onChange={(body) => patch({ body })} placeholder="Paste your SOAP envelope here" minHeight="360px" />
    </div>
  );
}

export function HeadersTab() {
  const { draft, update } = useWorkspace();
  const setRow = (id: string, change: Partial<{ name: string; value: string; enabled: boolean }>) =>
    update((d) => ({ ...d, headers: d.headers.map((h) => (h.id === id ? { ...h, ...change } : h)) }));
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-gray-500 dark:text-gray-400">
        Content-Type, Accept and, for SOAP 1.1, SOAPAction are added for you; a header you add here replaces the default of the
        same name. See the <span className="font-medium">Request</span> tab for the final list.
      </p>
      <datalist id="common-headers">{COMMON_HEADERS.map((h) => <option key={h} value={h} />)}</datalist>
      {draft.headers.length === 0 && (
        <div className="rounded-xl border border-dashed border-gray-300 px-4 py-6 text-center text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400">
          No custom headers.
        </div>
      )}
      {draft.headers.map((h) => (
        <div key={h.id} className="flex flex-col gap-1">
          <div className="flex items-center gap-2">
            <input
              type="checkbox"
              aria-label={`Send header ${h.name || "(unnamed)"}`}
              checked={h.enabled}
              onChange={(e) => setRow(h.id, { enabled: e.target.checked })}
              className="size-5 shrink-0 rounded border-gray-300 text-brand-500 focus:ring-brand-500/30 dark:border-gray-600 dark:bg-gray-900"
            />
            <Input aria-label="Header name" list="common-headers" placeholder="Name" className="w-2/5" value={h.name}
              onChange={(e) => setRow(h.id, { name: e.target.value })} />
            <Input aria-label="Header value" placeholder="Value" value={h.value} onChange={(e) => setRow(h.id, { value: e.target.value })} />
            <Button variant="ghost" size="icon" aria-label="Remove header"
              onClick={() => update((d) => ({ ...d, headers: d.headers.filter((x) => x.id !== h.id) }))}>
              <TrashBinIcon />
            </Button>
          </div>
          {draft.transport === "browser" && h.enabled && h.name && isForbiddenHeader(h.name) && (
            <p className="ps-7 text-xs text-amber-600 dark:text-amber-400">
              Browsers do not let a page set “{h.name}”, so it will not be sent. The SoapCLI server transport can.
            </p>
          )}
        </div>
      ))}
      <div>
        <Button variant="outline" size="sm" onClick={() => update((d) => ({ ...d, headers: [...d.headers, { id: newId(), name: "", value: "", enabled: true }] }))}>
          <PlusIcon /> Add header
        </Button>
      </div>
    </div>
  );
}

function SecretInput({ label, value, onChange, placeholder }: { label: string; value: string; onChange: (v: string) => void; placeholder?: string }) {
  const [shown, setShown] = useState(false);
  return (
    <div className="relative">
      <Input aria-label={label} type={shown ? "text" : "password"} autoComplete="off" className="pe-12" placeholder={placeholder}
        value={value} onChange={(e) => onChange(e.target.value)} />
      <button type="button" aria-label={shown ? "Hide" : "Show"} onClick={() => setShown(!shown)}
        className="absolute end-3 top-1/2 -translate-y-1/2 text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200">
        {shown ? <EyeCloseIcon className="size-5" /> : <EyeIcon className="size-5" />}
      </button>
    </div>
  );
}

export function AuthTab() {
  const { draft, patch } = useWorkspace();
  const auth = draft.auth;
  const set = (change: Partial<typeof auth>) => patch({ auth: { ...auth, ...change } });
  return (
    <div className="flex flex-col gap-4">
      <Segmented<AuthKind> label="Authentication" value={auth.kind} onChange={(kind) => set({ kind })}
        options={[{ value: "none", label: "None" }, { value: "basic", label: "Basic" }, { value: "bearer", label: "Bearer token" }]} />
      {auth.kind === "basic" && (
        <div className="grid gap-4 sm:grid-cols-2">
          <div><FieldLabel htmlFor="auth-user">Username</FieldLabel>
            <Input id="auth-user" autoComplete="off" value={auth.user} onChange={(e) => set({ user: e.target.value })} /></div>
          <div><FieldLabel>Password</FieldLabel>
            <SecretInput label="Password" value={auth.password} onChange={(password) => set({ password })} /></div>
        </div>
      )}
      {auth.kind === "bearer" && (
        <div><FieldLabel>Token</FieldLabel>
          <SecretInput label="Token" value={auth.token} onChange={(token) => set({ token })} placeholder="eyJhbGciOi…" /></div>
      )}
      <p className="text-sm text-gray-500 dark:text-gray-400">
        {draft.transport === "browser"
          ? "Credentials are sent from this browser straight to the endpoint."
          : "Credentials are sent to the SoapCLI server, which uses them for this call."}
        {" "}They are never kept in the history or the library, and are forgotten when you close this tab.
      </p>
    </div>
  );
}

export function VariablesTab() {
  const { draft, patch } = useWorkspace();
  const placeholders = useMemo(() => findPlaceholders(draft.body), [draft.body]);
  const set = (name: string, value: string) => {
    const args = { ...draft.args };
    if (value === "") delete args[name];
    else args[name] = value;
    patch({ args });
  };
  if (placeholders.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-gray-300 px-4 py-8 text-center text-sm text-gray-500 dark:border-gray-700 dark:text-gray-400">
        Write <code className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-700 dark:bg-white/10 dark:text-gray-300">___NAME:default___</code> anywhere
        in the request and it shows up here, so you can change the value without editing the XML.
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-gray-500 dark:text-gray-400">Leave a value empty to use the default written in the request.</p>
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
    </div>
  );
}

function cidFromFilename(name: string, taken: Set<string>): string {
  const base = name.replace(/\.[^.]+$/, "").replace(/[^A-Za-z0-9._~%@+-]/g, "_") || "attachment";
  let cid = base;
  for (let i = 2; taken.has(cid); i++) cid = `${base}-${i}`;
  return cid;
}

export function AttachmentsTab() {
  const { draft, patch, update } = useWorkspace();
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);

  const addFiles = async (files: FileList | File[]) => {
    const loaded = await Promise.all([...files].map(async (f) => ({ name: f.name, data: new Uint8Array(await f.arrayBuffer()) })));
    update((d) => {
      const taken = new Set(d.attachments.map((a) => a.cid));
      const added = loaded.map((f) => {
        const cid = cidFromFilename(f.name, taken);
        taken.add(cid);
        return { id: newId(), cid, filename: f.name, size: f.data.length, data: f.data };
      });
      return { ...d, attachments: [...d.attachments, ...added] };
    });
  };
  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    if (e.dataTransfer.files.length) void addFiles(e.dataTransfer.files);
  };

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-gray-500 dark:text-gray-400">
        Reference a file in the request as <code className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-700 dark:bg-white/10 dark:text-gray-300">cid:CID</code>,
        for example <code className="rounded bg-gray-100 px-1.5 py-0.5 text-gray-700 dark:bg-white/10 dark:text-gray-300">&lt;value&gt;cid:report&lt;/value&gt;</code>.
        By default it is replaced by the file's Base64; with MTOM it is sent as a separate MIME part.
      </p>
      <div
        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={`flex flex-col items-center gap-2 rounded-xl border border-dashed px-4 py-6 text-center transition-colors ${dragging ? "border-brand-400 bg-brand-50 dark:bg-brand-500/10" : "border-gray-300 dark:border-gray-700"}`}
      >
        <UploadIcon className="size-6 text-gray-400" />
        <Button variant="outline" size="sm" onClick={() => input.current?.click()}><PaperClipIcon /> Add files</Button>
        <span className="text-xs text-gray-500 dark:text-gray-400">or drop them here. They stay in memory and are not saved.</span>
        <input ref={input} type="file" multiple hidden onChange={(e) => { if (e.target.files) void addFiles(e.target.files); e.target.value = ""; }} />
      </div>
      {draft.attachments.map((a) => (
        <div key={a.id} className="grid items-center gap-2 sm:grid-cols-[minmax(0,1fr)_minmax(0,1.5fr)_auto_auto]">
          <Input aria-label={`CID of ${a.filename}`} value={a.cid}
            onChange={(e) => update((d) => ({ ...d, attachments: d.attachments.map((x) => (x.id === a.id ? { ...x, cid: e.target.value } : x)) }))} />
          <span className="truncate text-sm text-gray-700 dark:text-gray-300" title={a.filename}>{a.filename}</span>
          <Badge variant="light" color="gray" size="sm">{formatBytes(a.size)}</Badge>
          <Button variant="ghost" size="icon-sm" aria-label={`Remove ${a.filename}`}
            onClick={() => update((d) => ({ ...d, attachments: d.attachments.filter((x) => x.id !== a.id) }))}><TrashBinIcon /></Button>
        </div>
      ))}
      <label className="flex items-start gap-3">
        <Switch checked={draft.mtom} onCheckedChange={(mtom) => patch({ mtom })} aria-label="Send as MTOM" />
        <span>
          <span className="block text-sm font-medium text-gray-800 dark:text-white/90">Send as MTOM/XOP</span>
          <span className="block text-xs text-gray-500 dark:text-gray-400">
            A multipart/related message with the envelope and each file as a binary part, instead of Base64 inside the XML.
          </span>
        </span>
      </label>
    </div>
  );
}

export function OptionsTab() {
  const { draft, patch, server } = useWorkspace();
  const opts = draft.options;
  const set = (change: Partial<typeof opts>) => patch({ options: { ...opts, ...change } });
  const insecureAllowed = draft.transport === "server" && server.info?.insecureTlsAllowed !== false;
  return (
    <div className="flex flex-col gap-5">
      <div className="max-w-xs">
        <FieldLabel htmlFor="opt-timeout" hint="0 waits forever">Timeout (seconds)</FieldLabel>
        <Input id="opt-timeout" type="number" min={0} value={opts.timeoutSeconds}
          onChange={(e) => set({ timeoutSeconds: Math.max(0, Number(e.target.value) || 0) })} />
      </div>
      <label className="flex items-start gap-3">
        <Switch checked={opts.followRedirects} onCheckedChange={(followRedirects) => set({ followRedirects })} aria-label="Follow redirects" />
        <span>
          <span className="block text-sm font-medium text-gray-800 dark:text-white/90">Follow redirects</span>
          <span className="block text-xs text-gray-500 dark:text-gray-400">
            Off by default: a redirected POST is easy to lose. {draft.transport === "browser"
              ? "A browser turns a redirected POST into a GET and does not say where it went."
              : "The server re-sends the same POST and drops credentials if the redirect leaves the original host."}
          </span>
        </span>
      </label>
      <label className="flex items-start gap-3">
        <Switch checked={opts.insecure && insecureAllowed} disabled={!insecureAllowed} onCheckedChange={(insecure) => set({ insecure })}
          aria-label="Skip TLS certificate checks" />
        <span>
          <span className="block text-sm font-medium text-gray-800 dark:text-white/90">Skip TLS certificate checks</span>
          <span className="block text-xs text-gray-500 dark:text-gray-400">
            {draft.transport === "browser"
              ? "A browser cannot skip them. Open the URL in a tab and accept the certificate, or use the SoapCLI server transport."
              : insecureAllowed
                ? "For throw-away tests only: anyone on the network path could read or alter the traffic."
                : "Disabled by the server's administrator."}
          </span>
        </span>
      </label>
    </div>
  );
}
