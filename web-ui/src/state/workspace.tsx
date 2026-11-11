import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { RequestError, buildRequest } from "@/lib/request";
import { SAMPLE_BODY } from "@/lib/sample";
import {
  DEFAULT_SETTINGS, HISTORY_LIMIT, KEYS, fromStored, loadJson, saveJson, toStored,
  type HistoryEntry, type SavedRequest, type Settings, type StoredDraft,
} from "@/lib/storage";
import { TransportError, sendFromBrowser } from "@/lib/transports/browser";
import {
  ServerError, fetchServerInfo, fetchServerProfiles, previewViaServer, sendViaServer,
  type ServerInfo, type ServerProfiles,
} from "@/lib/transports/server";
import { emptyDraft, newId, type RequestDraft, type RequestView, type ResponseView } from "@/lib/types";

export type Page = "request" | "library" | "settings";

export interface UiError {
  kind: "config" | "timeout" | "network" | "cancelled" | "server";
  message: string;
  hints: string[];
  category?: string;
}

interface Workspace {
  page: Page;
  navigate: (page: Page) => void;

  draft: RequestDraft;
  patch: (patch: Partial<RequestDraft>) => void;
  update: (fn: (d: RequestDraft) => RequestDraft) => void;
  loadStored: (stored: StoredDraft) => void;
  resetDraft: () => void;

  response: ResponseView | null;
  error: UiError | null;
  sending: boolean;
  /** Sends the draft; `override` changes it first (for instance, to retry through the server). */
  send: (override?: Partial<RequestDraft>) => Promise<void>;
  cancel: () => void;
  clearResult: () => void;
  serverPreview: RequestView | null;
  refreshServerPreview: () => Promise<void>;

  history: HistoryEntry[];
  clearHistory: () => void;
  removeHistory: (id: string) => void;
  saved: SavedRequest[];
  saveCurrent: (name: string) => void;
  removeSaved: (id: string) => void;
  exportLibrary: () => string;
  importLibrary: (json: string) => number;
  clearAllData: () => void;

  settings: Settings;
  setSettings: (patch: Partial<Settings>) => void;

  server: { info: ServerInfo | null; loaded: boolean; profiles: ServerProfiles | null };
  refreshServer: () => void;
}

const WorkspaceContext = createContext<Workspace | null>(null);

function pageFromHash(): Page {
  const h = window.location.hash.replace(/^#\/?/, "");
  return h === "library" || h === "settings" ? h : "request";
}

function initialDraft(settings: Settings): RequestDraft {
  const stored = loadJson<Partial<StoredDraft> | undefined>(KEYS.draft, undefined);
  if (stored && typeof stored.body === "string") return fromStored(stored);
  const draft = emptyDraft();
  draft.body = SAMPLE_BODY;
  draft.transport = settings.defaultTransport;
  draft.options.timeoutSeconds = settings.timeoutSeconds;
  draft.options.followRedirects = settings.followRedirects;
  return draft;
}

function toUiError(e: unknown): UiError {
  if (e instanceof RequestError) return { kind: "config", message: e.message, hints: [] };
  if (e instanceof TransportError) return { kind: e.kind, message: e.message, hints: e.hints };
  if (e instanceof ServerError) return { kind: "server", message: e.message, hints: [], category: e.category };
  if (e instanceof DOMException && e.name === "AbortError") return { kind: "cancelled", message: "The request was cancelled.", hints: [] };
  return { kind: "network", message: e instanceof Error ? e.message : String(e), hints: [] };
}

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const [page, setPage] = useState<Page>(pageFromHash);
  const [settings, setSettingsState] = useState<Settings>(() => ({ ...DEFAULT_SETTINGS, ...loadJson<Partial<Settings>>(KEYS.settings, {}) }));
  const [draft, setDraft] = useState<RequestDraft>(() => initialDraft(settings));
  const [response, setResponse] = useState<ResponseView | null>(null);
  const [error, setError] = useState<UiError | null>(null);
  const [sending, setSending] = useState(false);
  const [serverPreview, setServerPreview] = useState<RequestView | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>(() => loadJson<HistoryEntry[]>(KEYS.history, []));
  const [saved, setSaved] = useState<SavedRequest[]>(() => loadJson<SavedRequest[]>(KEYS.saved, []));
  const [server, setServer] = useState<Workspace["server"]>({ info: null, loaded: false, profiles: null });
  const controller = useRef<AbortController | null>(null);

  // ---- navigation (hash based, so the server needs no routing) --------------------------------------------------
  useEffect(() => {
    const onHash = () => setPage(pageFromHash());
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  const navigate = useCallback((next: Page) => {
    window.location.hash = `/${next}`;
    setPage(next);
  }, []);

  // ---- persistence ----------------------------------------------------------------------------------------------
  useEffect(() => {
    const t = setTimeout(() => saveJson(KEYS.draft, toStored(draft)), 400);
    return () => clearTimeout(t);
  }, [draft]);
  useEffect(() => void saveJson(KEYS.history, history), [history]);
  useEffect(() => void saveJson(KEYS.saved, saved), [saved]);
  useEffect(() => void saveJson(KEYS.settings, settings), [settings]);

  // ---- the optional SoapCLI server ------------------------------------------------------------------------------
  const refreshServer = useCallback(() => {
    void (async () => {
      const info = await fetchServerInfo();
      let profiles: ServerProfiles | null = null;
      if (info) {
        try {
          profiles = await fetchServerProfiles();
        } catch {
          profiles = null;
        }
      }
      setServer({ info, loaded: true, profiles });
      if (!info) setDraft((d) => (d.transport === "server" ? { ...d, transport: "browser", profile: "" } : d));
    })();
  }, []);
  useEffect(() => refreshServer(), [refreshServer]);

  // ---- draft ----------------------------------------------------------------------------------------------------
  const patch = useCallback((p: Partial<RequestDraft>) => setDraft((d) => ({ ...d, ...p })), []);
  const update = useCallback((fn: (d: RequestDraft) => RequestDraft) => setDraft(fn), []);
  const clearResult = useCallback(() => {
    setResponse(null);
    setError(null);
    setServerPreview(null);
  }, []);
  const loadStored = useCallback((stored: StoredDraft) => {
    setDraft(fromStored(stored));
    clearResult();
  }, [clearResult]);
  const resetDraft = useCallback(() => {
    setDraft({ ...emptyDraft(), body: SAMPLE_BODY, transport: settings.defaultTransport,
      options: { timeoutSeconds: settings.timeoutSeconds, followRedirects: settings.followRedirects, insecure: false } });
    clearResult();
  }, [clearResult, settings]);

  // ---- sending --------------------------------------------------------------------------------------------------
  const send = useCallback(async (override?: Partial<RequestDraft>) => {
    const d = override ? { ...draft, ...override } : draft;
    if (override) setDraft(d);
    controller.current?.abort();
    const ctl = new AbortController();
    controller.current = ctl;
    setSending(true);
    setError(null);
    const record = (extra: Partial<HistoryEntry>) =>
      setHistory((h) => [{ id: newId(), at: Date.now(), draft: toStored(d), via: d.transport, ...extra }, ...h].slice(0, HISTORY_LIMIT));
    try {
      const view = d.transport === "server"
        ? await sendViaServer(d, ctl.signal)
        : await sendFromBrowser(buildRequest(d), d.options, ctl.signal);
      setResponse(view);
      record({ status: view.status, statusText: view.statusText, elapsedMs: view.elapsedMs });
    } catch (e) {
      const ui = toUiError(e);
      setError(ui);
      setResponse(null);
      if (ui.kind !== "cancelled") record({ error: ui.message });
    } finally {
      if (controller.current === ctl) setSending(false);
    }
  }, [draft]);
  const cancel = useCallback(() => controller.current?.abort(), []);

  const refreshServerPreview = useCallback(async () => {
    try {
      setServerPreview(await previewViaServer(draft));
      setError(null);
    } catch (e) {
      setError(toUiError(e));
    }
  }, [draft]);

  // ---- library --------------------------------------------------------------------------------------------------
  const saveCurrent = useCallback((name: string) => {
    setSaved((s) => [{ id: newId(), name: name.trim() || "Untitled request", savedAt: Date.now(), draft: toStored(draft) }, ...s]);
  }, [draft]);
  const removeSaved = useCallback((id: string) => setSaved((s) => s.filter((x) => x.id !== id)), []);
  const removeHistory = useCallback((id: string) => setHistory((h) => h.filter((x) => x.id !== id)), []);
  const clearHistory = useCallback(() => setHistory([]), []);

  const exportLibrary = useCallback(
    () => JSON.stringify({ app: "soapcli", version: 1, saved }, null, 2), [saved]);
  const importLibrary = useCallback((json: string): number => {
    const parsed = JSON.parse(json) as { app?: string; saved?: SavedRequest[] };
    if (parsed.app !== "soapcli" || !Array.isArray(parsed.saved)) throw new Error("This is not a SoapCLI export.");
    const incoming = parsed.saved
      .filter((s) => s && typeof s.name === "string" && s.draft && typeof s.draft.body === "string")
      .map((s) => ({ ...s, id: newId() }));
    setSaved((s) => [...incoming, ...s]);
    return incoming.length;
  }, []);
  const clearAllData = useCallback(() => {
    setHistory([]);
    setSaved([]);
  }, []);

  const setSettings = useCallback((p: Partial<Settings>) => setSettingsState((s) => ({ ...s, ...p })), []);

  const value = useMemo<Workspace>(() => ({
    page, navigate, draft, patch, update, loadStored, resetDraft, response, error, sending, send, cancel, clearResult,
    serverPreview, refreshServerPreview, history, clearHistory, removeHistory, saved, saveCurrent, removeSaved,
    exportLibrary, importLibrary, clearAllData, settings, setSettings, server, refreshServer,
  }), [page, navigate, draft, patch, update, loadStored, resetDraft, response, error, sending, send, cancel, clearResult,
    serverPreview, refreshServerPreview, history, clearHistory, removeHistory, saved, saveCurrent, removeSaved,
    exportLibrary, importLibrary, clearAllData, settings, setSettings, server, refreshServer]);

  return <WorkspaceContext.Provider value={value}>{children}</WorkspaceContext.Provider>;
}

export function useWorkspace(): Workspace {
  const ctx = useContext(WorkspaceContext);
  if (!ctx) throw new Error("useWorkspace must be used inside WorkspaceProvider");
  return ctx;
}
