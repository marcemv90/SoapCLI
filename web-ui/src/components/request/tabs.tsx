import { useState } from "react";
import { FieldLabel, Segmented } from "@/components/common/controls";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { EyeCloseIcon, EyeIcon, PlusIcon, TrashBinIcon } from "@/icons";
import { newId, type AuthKind } from "@/lib/types";
import { useWorkspace } from "@/state/workspace";

const COMMON_HEADERS = ["SOAPAction", "Accept", "Accept-Language", "Content-Type", "X-Correlation-ID", "X-Request-ID", "X-Api-Key", "X-Forwarded-For"];

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
        Credentials are sent to the SoapCLI server, which uses them for this call. They are never kept in the history or the library, and are forgotten when you close this tab.
      </p>
    </div>
  );
}

export function OptionsTab() {
  const { draft, patch, server } = useWorkspace();
  const opts = draft.options;
  const set = (change: Partial<typeof opts>) => patch({ options: { ...opts, ...change } });
  const insecureAllowed = server.info?.insecureTlsAllowed !== false;
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
            Off by default: a redirected POST is easy to lose. When on, the server re-sends the same POST and drops credentials if
            the redirect leaves the original host.
          </span>
        </span>
      </label>
      <label className="flex items-start gap-3">
        <Switch checked={opts.insecure && insecureAllowed} disabled={!insecureAllowed} onCheckedChange={(insecure) => set({ insecure })}
          aria-label="Skip TLS certificate checks" />
        <span>
          <span className="block text-sm font-medium text-gray-800 dark:text-white/90">Skip TLS certificate checks</span>
          <span className="block text-xs text-gray-500 dark:text-gray-400">
            {insecureAllowed
              ? "For throw-away tests only: anyone on the network path could read or alter the traffic."
              : "Disabled by the server's administrator."}
          </span>
        </span>
      </label>
    </div>
  );
}
