import { useRef, useState } from "react";
import { EmptyState, StatusBadge } from "@/components/common/controls";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AlertIcon, ClockIcon, DownloadIcon, FolderIcon, LinkIcon, SearchIcon, TrashBinIcon, UploadIcon } from "@/icons";
import { copyText, downloadFile, formatDuration, formatWhen } from "@/lib/format";
import { buildHash } from "@/lib/route";
import type { StoredDraft } from "@/lib/storage";
import type { SavedRequestSummary } from "@/lib/transports/server";
import { useApp } from "@/state/app";

const matches = (d: StoredDraft, extra: string, q: string) =>
  !q || `${extra} ${d.endpoint} ${d.soapAction}`.toLowerCase().includes(q.toLowerCase());

const matchesShared = (r: SavedRequestSummary, q: string) =>
  !q || `${r.name} ${r.folder ?? ""} ${r.endpoint ?? ""} ${r.soapAction ?? ""} ${r.profile ?? ""}`.toLowerCase().includes(q.toLowerCase());

const when = (iso?: string) => {
  const t = iso ? Date.parse(iso) : NaN;
  return Number.isNaN(t) ? "" : formatWhen(t);
};

/** The request is removed on the second click: a saved request of a workspace is everybody's. */
function DeleteButton({ label, onConfirm }: { label: string; onConfirm: () => void }) {
  const [armed, setArmed] = useState(false);
  return (
    <Button size={armed ? "xs" : "icon-xs"} variant={armed ? "destructive" : "ghost"} aria-label={armed ? `Confirm: ${label}` : label}
      onClick={() => { if (armed) { setArmed(false); onConfirm(); } else { setArmed(true); setTimeout(() => setArmed(false), 3000); } }}>
      {armed ? "Click again to delete" : <TrashBinIcon className="size-4" />}
    </Button>
  );
}

/** The saved requests of the workspace, grouped by folder. */
function WorkspaceRequests({ rows, total, onMessage }: { rows: SavedRequestSummary[]; total: number; onMessage: (m: { ok: boolean; text: string }) => void }) {
  const { server, workspace, openShared, removeShared } = useApp();
  const library = server.library;
  const folders = [...new Set(rows.map((r) => r.folder ?? ""))];

  const link = async (id: string) => {
    const url = `${window.location.origin}${window.location.pathname}${buildHash({ page: "request", workspace, open: id })}`;
    onMessage((await copyText(url)) ? { ok: true, text: "Link copied: whoever opens it gets this request, in this workspace." } : { ok: false, text: "Could not copy the link." });
  };
  const remove = async (r: SavedRequestSummary) => {
    try {
      await removeShared(r.id, r.revision ?? "");
    } catch (e) {
      onMessage({ ok: false, text: e instanceof Error ? e.message : String(e) });
    }
  };

  if (library === null) return <EmptyState icon={<FolderIcon />} title="Loading…">Reading the saved requests of this workspace.</EmptyState>;
  return (
    <>
      {!library.editable && library.readOnlyReason && (
        <p className="border-b border-gray-200 px-4 py-3 text-xs text-gray-500 sm:px-5 dark:border-gray-800 dark:text-gray-400">{library.readOnlyReason}</p>
      )}
      {rows.length === 0 ? (
        <EmptyState icon={<FolderIcon />} title={total === 0 ? "Nothing saved in this workspace yet" : "No match"}>
          {total === 0
            ? "Use Save on the Request page and choose the workspace: the request, its headers and variables are kept on the server, for everyone who opens it."
            : "Try another search."}
        </EmptyState>
      ) : (
        folders.map((folder) => (
          <section key={folder} aria-label={folder || "Requests without a folder"}>
            {(folders.length > 1 || folder !== "") && (
              <h3 className="flex items-center gap-2 border-b border-gray-200 bg-gray-50 px-4 py-2 text-xs font-semibold tracking-wide text-gray-500 uppercase sm:px-5 dark:border-gray-800 dark:bg-white/3 dark:text-gray-400">
                <FolderIcon className="size-4" /> {folder || "No folder"}
              </h3>
            )}
            <ul className="divide-y divide-gray-200 dark:divide-gray-800">
              {rows.filter((r) => (r.folder ?? "") === folder).map((r) => (
                <li key={r.id} className="flex flex-wrap items-center gap-3 px-4 py-4 sm:px-5">
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="truncate font-medium text-gray-800 dark:text-white/90">{r.name}</span>
                      {r.profile && <Badge variant="light" color="gray" size="sm">profile {r.profile}</Badge>}
                    </div>
                    {r.error ? (
                      <div className="flex items-start gap-1.5 text-xs text-red-600 dark:text-red-400"><AlertIcon className="mt-0.5 size-3.5 shrink-0" /> {r.error}</div>
                    ) : (
                      <div className="truncate font-mono text-xs text-gray-500 dark:text-gray-400">{r.endpoint || "(no endpoint)"}</div>
                    )}
                  </div>
                  <span className="text-xs text-gray-500 dark:text-gray-400">{when(r.updatedAt)}</span>
                  {!r.error && <Button size="xs" onClick={() => void openShared(r.id)}>Open</Button>}
                  {!r.error && <Button size="icon-xs" variant="ghost" aria-label={`Copy a link to ${r.name}`} title="Copy a link to this request" onClick={() => void link(r.id)}><LinkIcon className="size-4" /></Button>}
                  {library.editable && <DeleteButton label={`Delete ${r.name}`} onConfirm={() => void remove(r)} />}
                </li>
              ))}
            </ul>
          </section>
        ))
      )}
    </>
  );
}

export function LibraryPage() {
  const {
    saved, history, loadStored, navigate, removeSaved, removeHistory, clearHistory, exportLibrary, importLibrary,
    workspacesEnabled, workspace, server, moveToWorkspace,
  } = useApp();
  const [query, setQuery] = useState("");
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const [tab, setTab] = useState(workspacesEnabled ? "workspace" : "saved");
  const fileInput = useRef<HTMLInputElement>(null);
  const library = server.library;
  const shared = library?.requests ?? [];
  // On a server with workspaces the browser's own list is only shown while there is something in it.
  const showLocal = !workspacesEnabled || saved.length > 0;
  const current = showLocal || tab !== "saved" ? tab : "workspace";

  const open = (d: StoredDraft) => {
    loadStored(d);
    navigate("request");
  };
  const onImport = async (file: File) => {
    try {
      const n = importLibrary(await file.text());
      setMessage({ ok: true, text: `Imported ${n} request${n === 1 ? "" : "s"}.` });
    } catch (e) {
      setMessage({ ok: false, text: e instanceof Error ? e.message : "Could not read that file." });
    }
  };
  const move = async (ids: string[]) => {
    const warnings: string[] = [];
    let moved = 0;
    try {
      for (const id of ids) {
        warnings.push(...(await moveToWorkspace(id)));
        moved += 1;
      }
      setMessage({ ok: true, text: `Moved ${moved} request${moved === 1 ? "" : "s"} to the workspace “${workspace}”.${warnings.length ? ` ${[...new Set(warnings)].join(" ")}` : ""}` });
    } catch (e) {
      setMessage({ ok: false, text: `${moved > 0 ? `Moved ${moved}, then: ` : ""}${e instanceof Error ? e.message : String(e)}` });
    }
  };

  const savedRows = saved.filter((s) => matches(s.draft, s.name, query));
  const historyRows = history.filter((h) => matches(h.draft, h.error ?? "", query));
  const sharedRows = shared.filter((r) => matchesShared(r, query));

  return (
    <div className="flex flex-col gap-4 md:gap-6">
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative min-w-60 flex-1 sm:max-w-md">
          <SearchIcon className="pointer-events-none absolute start-3.5 top-1/2 size-5 -translate-y-1/2 text-gray-400" />
          <Input aria-label="Search" className="ps-11" placeholder="Search by name or URL" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        {current === "saved" && (
          <div className="ms-auto flex gap-2">
            <Button variant="outline" size="sm" onClick={() => downloadFile("soapcli-library.json", exportLibrary(), "application/json")} disabled={saved.length === 0}>
              <DownloadIcon /> Export
            </Button>
            <Button variant="outline" size="sm" onClick={() => fileInput.current?.click()}><UploadIcon /> Import</Button>
            <input ref={fileInput} type="file" accept="application/json,.json" hidden
              onChange={(e) => { const f = e.target.files?.[0]; if (f) void onImport(f); e.target.value = ""; }} />
          </div>
        )}
      </div>
      {message && <Alert variant={message.ok ? "success" : "destructive"}><AlertDescription>{message.text}</AlertDescription></Alert>}

      <Card className="gap-0 overflow-hidden p-0 sm:p-0">
        <Tabs value={current} onValueChange={(v) => { setTab(String(v)); setMessage(null); }} className="gap-0">
          <TabsList variant="line" className="px-3 sm:px-4">
            {workspacesEnabled && <TabsTrigger value="workspace">Workspace ({shared.length})</TabsTrigger>}
            {!workspacesEnabled && <TabsTrigger value="saved">Saved ({saved.length})</TabsTrigger>}
            <TabsTrigger value="history">History ({history.length})</TabsTrigger>
            {workspacesEnabled && showLocal && <TabsTrigger value="saved">This browser ({saved.length})</TabsTrigger>}
          </TabsList>
          {workspacesEnabled && (
            <TabsContent value="workspace">
              <WorkspaceRequests rows={sharedRows} total={shared.length} onMessage={setMessage} />
            </TabsContent>
          )}
          <TabsContent value="saved">
            {workspacesEnabled && (
              <div className="flex flex-wrap items-center gap-3 border-b border-gray-200 px-4 py-3 sm:px-5 dark:border-gray-800">
                <p className="min-w-0 flex-1 text-xs text-gray-500 dark:text-gray-400">
                  These were saved in this browser, and nobody else can see them. Move them to the workspace to share them.
                </p>
                {library?.editable && saved.length > 0 && (
                  <Button size="xs" variant="outline" onClick={() => void move(saved.map((s) => s.id))}>Move all to {workspace}</Button>
                )}
              </div>
            )}
            {savedRows.length === 0 ? (
              <EmptyState icon={<FolderIcon />} title={saved.length === 0 ? "Nothing saved yet" : "No match"}>
                {saved.length === 0 ? "Use Save on the Request page to keep a request, with its headers and variables, for later." : "Try another search."}
              </EmptyState>
            ) : (
              <ul className="divide-y divide-gray-200 dark:divide-gray-800">
                {savedRows.map((s) => (
                  <li key={s.id} className="flex flex-wrap items-center gap-3 px-4 py-4 sm:px-5">
                    <div className="min-w-0 flex-1">
                      <div className="truncate font-medium text-gray-800 dark:text-white/90">{s.name}</div>
                      <div className="truncate font-mono text-xs text-gray-500 dark:text-gray-400">{s.draft.endpoint || "(no endpoint)"}</div>
                    </div>
                    <span className="text-xs text-gray-500 dark:text-gray-400">{formatWhen(s.savedAt)}</span>
                    <Button size="xs" onClick={() => open(s.draft)}>Open</Button>
                    {workspacesEnabled && library?.editable && (
                      <Button size="xs" variant="outline" onClick={() => void move([s.id])} title={`Save it in the workspace ${workspace} and remove it from this browser`}>Move to workspace</Button>
                    )}
                    <Button size="icon-xs" variant="ghost" aria-label={`Delete ${s.name}`} onClick={() => removeSaved(s.id)}><TrashBinIcon className="size-4" /></Button>
                  </li>
                ))}
              </ul>
            )}
          </TabsContent>
          <TabsContent value="history">
            {historyRows.length === 0 ? (
              <EmptyState icon={<ClockIcon />} title={history.length === 0 ? "No requests yet" : "No match"}>
                {history.length === 0
                  ? `Every request you send${workspacesEnabled ? " from this workspace" : ""} is listed here (the last 50), without passwords or tokens. It is kept in this browser only.`
                  : "Try another search."}
              </EmptyState>
            ) : (
              <>
                <ul className="divide-y divide-gray-200 dark:divide-gray-800">
                  {historyRows.map((h) => (
                    <li key={h.id} className="flex flex-wrap items-center gap-3 px-4 py-3.5 sm:px-5">
                      {h.status !== undefined ? <StatusBadge status={h.status} text={h.statusText} /> : <Badge variant="light" color="error" size="lg">Failed</Badge>}
                      <div className="min-w-0 flex-1">
                        <div className="truncate font-mono text-xs text-gray-800 dark:text-white/90">{h.draft.endpoint || "(no endpoint)"}</div>
                        <div className="truncate text-xs text-gray-500 dark:text-gray-400">
                          {formatWhen(h.at)}{h.draft.profile ? ` · profile ${h.draft.profile}` : ""}{h.elapsedMs !== undefined ? ` · ${formatDuration(h.elapsedMs)}` : ""}{h.error ? ` · ${h.error}` : ""}
                        </div>
                      </div>
                      <Button size="xs" variant="outline" onClick={() => open(h.draft)}>Open</Button>
                      <Button size="icon-xs" variant="ghost" aria-label="Remove from history" onClick={() => removeHistory(h.id)}><TrashBinIcon className="size-4" /></Button>
                    </li>
                  ))}
                </ul>
                <div className="border-t border-gray-200 px-4 py-3 text-end sm:px-5 dark:border-gray-800">
                  <Button variant="ghost" size="sm" onClick={clearHistory}>Clear history</Button>
                </div>
              </>
            )}
          </TabsContent>
        </Tabs>
      </Card>
    </div>
  );
}
