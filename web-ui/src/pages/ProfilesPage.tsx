import { useState, type ReactNode } from "react";
import { CopyButton, EmptyState } from "@/components/common/controls";
import { ProfileForm } from "@/components/profiles/ProfileForm";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AlertIcon, PlusIcon, TagIcon } from "@/icons";
import { emptyDefinition } from "@/lib/profile-form";
import {
  deleteProfile, fetchProfileDefinition, fetchProfileRequest, type ProfileDefinition, type SaveResult, type ServerProfile,
} from "@/lib/transports/server";
import { useApp } from "@/state/app";
import { cn } from "@/utils";

/** The order of the profiles documentation; anything else follows alphabetically. */
const KEY_ORDER = [
  "extends", "endpoint", "requestFile", "soapVersion", "soapAction", "mtom", "timeout", "connectTimeout", "followRedirects",
  "headers", "args", "attachments", "authBasic", "authBearer", "keystore", "truststore", "insecure", "proxy", "theme",
];
const MASK = "****";

const isObject = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);

function orderedEntries(settings: Record<string, unknown>): [string, unknown][] {
  const rank = (k: string) => (KEY_ORDER.includes(k) ? KEY_ORDER.indexOf(k) : KEY_ORDER.length);
  return Object.entries(settings).sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b));
}

function Value({ value }: { value: unknown }) {
  if (value === MASK) {
    return <span className="text-gray-500 dark:text-gray-400">{MASK} <span className="font-sans">(set, hidden)</span></span>;
  }
  if (typeof value === "boolean") return <Badge variant="light" color={value ? "success" : "gray"} size="sm">{String(value)}</Badge>;
  if (value === null || value === "") return <span className="text-gray-400">(empty)</span>;
  if (Array.isArray(value)) return <>{value.map(String).join(", ")}</>;
  return <>{String(value)}</>;
}

function Rows({ data }: { data: Record<string, unknown> }) {
  const rows = orderedEntries(data);
  if (rows.length === 0) return <p className="px-4 py-2.5 text-sm text-gray-500 dark:text-gray-400">Nothing set.</p>;
  return (
    <table className="min-w-full text-sm">
      <tbody className="divide-y divide-gray-200 dark:divide-gray-800">
        {rows.map(([name, value]) => (
          <tr key={name}>
            <th scope="row" className="w-2/5 bg-gray-50 px-4 py-2.5 text-start align-top font-medium break-all text-gray-700 dark:bg-white/3 dark:text-gray-300">{name}</th>
            <td className="px-4 py-2.5 font-mono text-xs break-all text-gray-800 dark:text-white/90"><Value value={value} /></td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function Group({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <div className="overflow-hidden rounded-xl border border-gray-200 dark:border-gray-800">
      {title && <h4 className="border-b border-gray-200 bg-gray-50 px-4 py-2.5 text-sm font-semibold text-gray-800 dark:border-gray-800 dark:bg-white/3 dark:text-white/90">{title}</h4>}
      <div className="overflow-x-auto">{children}</div>
    </div>
  );
}

/** The effective settings: plain values first, then one table for each group (headers, proxy, keystore...). */
function SettingsView({ settings }: { settings: Record<string, unknown> }) {
  const entries = orderedEntries(settings);
  const plain = Object.fromEntries(entries.filter(([, v]) => !isObject(v)));
  const groups = entries.filter(([, v]) => isObject(v)) as [string, Record<string, unknown>][];
  if (entries.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">This profile sets nothing of its own.</p>;
  return (
    <div className="flex flex-col gap-4">
      {Object.keys(plain).length > 0 && <Group><Rows data={plain} /></Group>}
      {groups.map(([name, data]) => <Group key={name} title={name}><Rows data={data} /></Group>)}
    </div>
  );
}

function ConfirmButton({ label, confirm, onConfirm, busy }: { label: string; confirm: string; onConfirm: () => void; busy?: boolean }) {
  const [armed, setArmed] = useState(false);
  return (
    <Button variant={armed ? "destructive" : "outline"} size="sm" disabled={busy}
      onClick={() => { if (armed) { setArmed(false); onConfirm(); } else { setArmed(true); setTimeout(() => setArmed(false), 3000); } }}>
      {busy && <Spinner />} {armed ? confirm : label}
    </Button>
  );
}

function ProfileDetail({ profile, editable, onEdit, onDelete, busy }: {
  profile: ServerProfile;
  editable: boolean;
  onEdit: () => void;
  onDelete: () => void;
  busy: "edit" | "delete" | null;
}) {
  const { patch, navigate } = useApp();
  const [problem, setProblem] = useState<string | null>(null);
  const settings = profile.settings ?? {};
  const parent = typeof settings.extends === "string" ? settings.extends : null;
  // `extends` is shown in the header, not as a setting.
  const { extends: _extends, ...effective } = settings;

  const use = () => {
    patch({ profile: profile.name });
    navigate("request");
  };
  const loadRequest = async () => {
    try {
      const body = await fetchProfileRequest(profile.name);
      patch({ profile: profile.name, body });
      navigate("request");
    } catch (e) {
      setProblem(e instanceof Error ? e.message : "Could not load the request file.");
    }
  };

  return (
    <>
      <div className="flex flex-wrap items-center gap-3 border-b border-gray-200 px-4 py-4 sm:px-5 dark:border-gray-800">
        <h2 className="text-base font-semibold text-gray-800 dark:text-white/90">{profile.name}</h2>
        {parent && <Badge variant="light" color="gray" size="sm">extends {parent}</Badge>}
        <div className="ms-auto flex flex-wrap gap-2">
          {editable && <Button variant="outline" size="sm" onClick={onEdit} disabled={busy !== null}>{busy === "edit" && <Spinner />} Edit</Button>}
          {editable && <ConfirmButton label="Delete" confirm="Click again to delete" onConfirm={onDelete} busy={busy === "delete"} />}
          {profile.hasRequestFile && <Button variant="outline" size="sm" onClick={() => void loadRequest()}>Load its request file</Button>}
          <Button size="sm" onClick={use} disabled={!!profile.error}>Use in the request</Button>
        </div>
      </div>
      <div className="flex flex-col gap-4 p-4 sm:p-5">
        {problem && <Alert variant="destructive"><AlertIcon /><AlertDescription>{problem}</AlertDescription></Alert>}
        {profile.error ? (
          <Alert variant="destructive"><AlertIcon /><AlertTitle>This profile cannot be used</AlertTitle><AlertDescription>{profile.error}</AlertDescription></Alert>
        ) : (
          <Tabs defaultValue="settings" className="gap-4">
            <TabsList variant="line" className="-mx-1">
              <TabsTrigger value="settings">Settings</TabsTrigger>
              <TabsTrigger value="json">JSON</TabsTrigger>
            </TabsList>
            <TabsContent value="settings" className="flex flex-col gap-3">
              <p className="text-xs text-gray-500 dark:text-gray-400">
                What this profile resolves to after <code>extends</code>. Secrets are hidden; <code>${"{VAR}"}</code> references are shown as
                written and expanded by the server from its own environment.
              </p>
              <SettingsView settings={effective} />
            </TabsContent>
            <TabsContent value="json" className="flex flex-col gap-3">
              <div className="flex justify-end"><CopyButton text={JSON.stringify(settings, null, 2)} /></div>
              <pre className="max-h-[60vh] overflow-auto rounded-xl border border-gray-200 bg-gray-50 p-4 font-mono text-xs text-gray-800 dark:border-gray-800 dark:bg-white/3 dark:text-white/90">
                {JSON.stringify(settings, null, 2)}
              </pre>
            </TabsContent>
          </Tabs>
        )}
      </div>
    </>
  );
}

type Mode = { kind: "view" } | { kind: "create" } | { kind: "edit"; definition: ProfileDefinition; round: number };

export function ProfilesPage() {
  const { server, draft, refreshServer } = useApp();
  const [chosen, setChosen] = useState<string | null>(null);
  const [mode, setMode] = useState<Mode>({ kind: "view" });
  const [busy, setBusy] = useState<"edit" | "delete" | null>(null);
  const [notice, setNotice] = useState<{ ok: boolean; text: string; warnings: string[] } | null>(null);
  const info = server.info;
  const result = server.profiles;
  const profiles = result?.profiles ?? [];
  const selected = profiles.find((p) => p.name === (chosen ?? draft.profile)) ?? profiles[0];
  const editing = mode.kind !== "view";
  const editable = result?.editable === true;

  if (server.loaded && !info) {
    return (
      <Card>
        <EmptyState icon={<TagIcon />} title="No SoapCLI server">
          Profiles live on the SoapCLI server, and this page was not served by one.
        </EmptyState>
      </Card>
    );
  }

  const fail = (e: unknown) => setNotice({ ok: false, text: e instanceof Error ? e.message : String(e), warnings: [] });
  const edit = async (name: string) => {
    setBusy("edit");
    setNotice(null);
    try {
      setMode({ kind: "edit", definition: await fetchProfileDefinition(name), round: 0 });
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  };
  const reload = async () => {
    await refreshServer();
    if (mode.kind === "edit") {
      try {
        const definition = await fetchProfileDefinition(mode.definition.name);
        // Only if the form is still open: someone may have cancelled while the file was being read again.
        setMode((now) => (now.kind === "edit" ? { kind: "edit", definition, round: now.round + 1 } : now));
      } catch (e) {
        setMode({ kind: "view" });
        fail(e);
      }
    } else {
      setMode({ kind: "view" });
    }
  };
  const saved = async (name: string, res: SaveResult) => {
    await refreshServer(); // show the list as it is now, not for a moment as it was
    setChosen(name);
    setMode({ kind: "view" });
    setNotice({ ok: true, text: `Saved “${name}”.`, warnings: res.warnings });
  };
  const remove = async (name: string) => {
    setBusy("delete");
    setNotice(null);
    try {
      const res = await deleteProfile(name, result?.revision ?? "");
      await refreshServer();
      setChosen(null);
      setNotice({ ok: true, text: `Deleted “${name}”.`, warnings: res.warnings });
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="flex flex-col gap-4 md:gap-6">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm text-gray-500 dark:text-gray-400">
        <span>Profiles file:</span>
        <code className="rounded bg-gray-100 px-1.5 py-0.5 break-all text-gray-700 dark:bg-white/10 dark:text-gray-300">{result?.file ?? info?.profilesFile ?? "…"}</code>
        <Button variant="outline" size="xs" onClick={refreshServer} disabled={editing}>Reload</Button>
        {editable && <Button size="xs" onClick={() => { setNotice(null); setMode({ kind: "create" }); }} disabled={editing}><PlusIcon /> New profile</Button>}
        <span className="text-xs">
          {editable ? "Changes are written to this file; the rest of it, comments included, is left as it is."
            : result?.readOnlyReason ?? "The server reads it on every request, so edits apply immediately."}
        </span>
      </div>

      {notice && (
        <Alert variant={notice.ok ? "success" : "destructive"}>
          {!notice.ok && <AlertIcon />}
          <AlertDescription>
            {notice.text}
            {notice.warnings.length > 0 && <ul className="mt-1 list-disc ps-4">{notice.warnings.map((w) => <li key={w}>{w}</li>)}</ul>}
          </AlertDescription>
        </Alert>
      )}

      {result?.error && (
        <Alert variant="destructive"><AlertIcon /><AlertTitle>The profiles file cannot be read</AlertTitle><AlertDescription>{result.error}</AlertDescription></Alert>
      )}

      {result && !result.found && mode.kind !== "create" && (
        <Card>
          <EmptyState icon={<TagIcon />} title="No profiles file"
            action={editable ? <Button size="sm" onClick={() => setMode({ kind: "create" })}><PlusIcon /> Create the first profile</Button> : undefined}>
            {editable
              ? "Create a profile and the file is created with it. A profile keeps endpoints, headers, credentials, certificates and a proxy under a name."
              : "Create the file above (or point the server to another one with --profile-file) to keep endpoints, headers, credentials, certificates and proxies under a name. See the documentation on profiles."}
          </EmptyState>
        </Card>
      )}

      {result?.found && !result.error && profiles.length === 0 && mode.kind !== "create" && (
        <Card><EmptyState icon={<TagIcon />} title="The file has no profiles">Add one under the top-level "profiles" object.</EmptyState></Card>
      )}

      {(selected || mode.kind === "create") && (
        <div className="grid grid-cols-1 items-start gap-4 md:gap-6 xl:grid-cols-[18rem_minmax(0,1fr)]">
          <Card className="gap-0 overflow-hidden p-0 sm:p-0">
            <ul aria-label="Profiles" className="divide-y divide-gray-200 dark:divide-gray-800">
              {profiles.map((p) => {
                const active = mode.kind !== "create" && p.name === selected?.name;
                const endpoint = typeof p.settings?.endpoint === "string" ? p.settings.endpoint : "";
                return (
                  <li key={p.name}>
                    <button
                      type="button"
                      aria-current={active ? "true" : undefined}
                      disabled={editing}
                      title={editing ? "Finish or cancel the edit first" : undefined}
                      onClick={() => { setChosen(p.name); setNotice(null); }}
                      className={cn(
                        "flex w-full flex-col items-start gap-0.5 px-4 py-3 text-start transition-colors outline-hidden focus-visible:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-60 dark:focus-visible:bg-white/5",
                        active ? "bg-brand-50 dark:bg-brand-500/10" : "hover:bg-gray-50 dark:hover:bg-white/5",
                      )}
                    >
                      <span className="flex w-full items-center gap-2">
                        <span className="truncate text-sm font-medium text-gray-800 dark:text-white/90">{p.name}</span>
                        {p.error && <Badge variant="light" color="error" size="sm">error</Badge>}
                        {draft.profile === p.name && <Badge variant="light" color="primary" size="sm" className="ms-auto">in use</Badge>}
                      </span>
                      {endpoint && <span className="w-full truncate font-mono text-xs text-gray-500 dark:text-gray-400">{endpoint}</span>}
                    </button>
                  </li>
                );
              })}
              {mode.kind === "create" && <li className="bg-brand-50 px-4 py-3 text-sm font-medium text-gray-800 dark:bg-brand-500/10 dark:text-white/90">New profile</li>}
            </ul>
          </Card>
          <Card className="min-w-0 gap-0 overflow-hidden p-0 sm:p-0">
            {mode.kind === "create" ? (
              <>
                <div className="border-b border-gray-200 px-4 py-4 sm:px-5 dark:border-gray-800"><h2 className="text-base font-semibold text-gray-800 dark:text-white/90">New profile</h2></div>
                <div className="p-4 sm:p-5">
                  <ProfileForm key="create" mode="create" name="" initial={emptyDefinition()} revision={result?.revision ?? "missing"} dependents={[]}
                    profiles={profiles} onSaved={saved} onCancel={() => setMode({ kind: "view" })} onReload={() => void reload()} />
                </div>
              </>
            ) : mode.kind === "edit" ? (
              <>
                <div className="border-b border-gray-200 px-4 py-4 sm:px-5 dark:border-gray-800">
                  <h2 className="text-base font-semibold text-gray-800 dark:text-white/90">Edit {mode.definition.name}</h2>
                </div>
                <div className="p-4 sm:p-5">
                  <ProfileForm key={`${mode.definition.name}-${mode.round}`} mode="edit" name={mode.definition.name} initial={mode.definition.definition}
                    revision={mode.definition.revision} dependents={mode.definition.dependents} profiles={profiles}
                    onSaved={saved} onCancel={() => setMode({ kind: "view" })} onReload={() => void reload()} />
                </div>
              </>
            ) : selected ? (
              <ProfileDetail key={selected.name} profile={selected} editable={editable} busy={busy}
                onEdit={() => void edit(selected.name)} onDelete={() => void remove(selected.name)} />
            ) : null}
          </Card>
        </div>
      )}
    </div>
  );
}
