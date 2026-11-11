import { useRef, useState } from "react";
import { EmptyState, StatusBadge } from "@/components/common/controls";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { ClockIcon, DownloadIcon, FolderIcon, SearchIcon, TrashBinIcon, UploadIcon } from "@/icons";
import { downloadFile, formatDuration, formatWhen } from "@/lib/format";
import type { StoredDraft } from "@/lib/storage";
import { useWorkspace } from "@/state/workspace";

const matches = (d: StoredDraft, extra: string, q: string) =>
  !q || `${extra} ${d.endpoint} ${d.soapAction}`.toLowerCase().includes(q.toLowerCase());

export function LibraryPage() {
  const { saved, history, loadStored, navigate, removeSaved, removeHistory, clearHistory, exportLibrary, importLibrary } = useWorkspace();
  const [query, setQuery] = useState("");
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null);
  const fileInput = useRef<HTMLInputElement>(null);

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

  const savedRows = saved.filter((s) => matches(s.draft, s.name, query));
  const historyRows = history.filter((h) => matches(h.draft, h.error ?? "", query));

  return (
    <div className="flex flex-col gap-4 md:gap-6">
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative min-w-60 flex-1 sm:max-w-md">
          <SearchIcon className="pointer-events-none absolute start-3.5 top-1/2 size-5 -translate-y-1/2 text-gray-400" />
          <Input aria-label="Search" className="ps-11" placeholder="Search by name or URL" value={query} onChange={(e) => setQuery(e.target.value)} />
        </div>
        <div className="ms-auto flex gap-2">
          <Button variant="outline" size="sm" onClick={() => downloadFile("soapcli-library.json", exportLibrary(), "application/json")} disabled={saved.length === 0}>
            <DownloadIcon /> Export
          </Button>
          <Button variant="outline" size="sm" onClick={() => fileInput.current?.click()}><UploadIcon /> Import</Button>
          <input ref={fileInput} type="file" accept="application/json,.json" hidden
            onChange={(e) => { const f = e.target.files?.[0]; if (f) void onImport(f); e.target.value = ""; }} />
        </div>
      </div>
      {message && <Alert variant={message.ok ? "success" : "destructive"}><AlertDescription>{message.text}</AlertDescription></Alert>}

      <Card className="gap-0 overflow-hidden p-0 sm:p-0">
        <Tabs defaultValue="saved" className="gap-0">
          <TabsList variant="line" className="px-3 sm:px-4">
            <TabsTrigger value="saved">Saved ({saved.length})</TabsTrigger>
            <TabsTrigger value="history">History ({history.length})</TabsTrigger>
          </TabsList>
          <TabsContent value="saved">
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
                    <Button size="icon-xs" variant="ghost" aria-label={`Delete ${s.name}`} onClick={() => removeSaved(s.id)}><TrashBinIcon className="size-4" /></Button>
                  </li>
                ))}
              </ul>
            )}
          </TabsContent>
          <TabsContent value="history">
            {historyRows.length === 0 ? (
              <EmptyState icon={<ClockIcon />} title={history.length === 0 ? "No requests yet" : "No match"}>
                {history.length === 0 ? "Every request you send is listed here (the last 50), without passwords or tokens." : "Try another search."}
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
                          {formatWhen(h.at)} · {h.via === "browser" ? "browser" : "server"}{h.elapsedMs !== undefined ? ` · ${formatDuration(h.elapsedMs)}` : ""}{h.error ? ` · ${h.error}` : ""}
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
