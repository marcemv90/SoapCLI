import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { pathAttachments } from "@/lib/attachments";
import { MIN_VISIBLE_MS, loadAttachments, pause, pendingItems, type LoadItem } from "@/lib/load-attachments";
import { WORKSPACE_NAME, buildHash, parseRoute, type Page, type Route } from "@/lib/route";
import { SAMPLE_BODY } from "@/lib/sample";
import {
  HISTORY_LIMIT, KEYS, fromStored, loadJson, loadSettings, removeKey, saveJson, scopedKeys, toStored,
  type HistoryEntry, type SavedRequest, type Settings, type StoredDraft,
} from "@/lib/storage";
import {
  ServerError, checkAttachment, createWorkspace, deleteSavedRequest, fetchLibrary, fetchPlaceholders, fetchSavedRequest,
  fetchServerInfo, fetchServerProfiles, fetchWorkspaceInfo, fetchWorkspaces, previewViaServer, removeWorkspace, saveSavedRequest,
  sendViaServer, setWorkspace as setApiWorkspace,
  type ServerInfo, type ServerLibrary, type ServerPlaceholders, type ServerProfiles, type ServerWorkspaces, type WorkspaceSummary,
} from "@/lib/transports/server";
import { emptyDraft, newId, type RequestDraft, type RequestView, type ResponseView } from "@/lib/types";

export type { Page } from "@/lib/route";

export interface UiError {
  kind: "server" | "cancelled";
  message: string;
  /** What the server called the problem: config, forbidden, network, unsupported, too-large, internal, unavailable. */
  category?: string;
}

/** The files a request names by path, while the server loads them (running) or after one could not be (failed). */
export interface AttachmentLoad {
  items: LoadItem[];
  status: "running" | "failed";
}

/** Opens the Placeholders page on one placeholder: to give a new one its options, or to edit them. */
export interface PlaceholderIntent {
  name: string;
}

/** The saved request of a workspace that the request being edited was opened from: saving can update it. */
export interface SavedOrigin {
  id: string;
  name: string;
  folder: string;
  /** The version of the saved request that was opened; saving over it quotes this one. */
  revision: string;
}

/** Something to tell the person on the request page: what happened when a request was opened, say. */
export interface Notice {
  kind: "info" | "error";
  text: string;
}

interface ServerState {
  info: ServerInfo | null;
  loaded: boolean;
  profiles: ServerProfiles | null;
  placeholders: ServerPlaceholders | null;
  /** The saved requests of the open workspace; only with workspaces. */
  library: ServerLibrary | null;
  /** The workspaces of the server; only with workspaces. */
  workspaces: ServerWorkspaces | null;
}

interface AppState {
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
  send: () => Promise<void>;
  cancel: () => void;
  /** Set while the attachments named by path are loaded before sending; the request page shows it in a dialog. */
  attachmentLoad: AttachmentLoad | null;
  dismissAttachmentLoad: () => void;
  clearResult: () => void;
  serverPreview: RequestView | null;
  refreshServerPreview: () => Promise<void>;
  notice: Notice | null;
  dismissNotice: () => void;

  /** The calls of the open workspace (this browser's history, kept per workspace). */
  history: HistoryEntry[];
  clearHistory: () => void;
  removeHistory: (id: string) => void;
  /** The requests saved in this browser (private to it). The ones of a workspace are in `server.library`. */
  saved: SavedRequest[];
  saveCurrent: (name: string) => void;
  removeSaved: (id: string) => void;
  exportLibrary: () => string;
  importLibrary: (json: string) => number;
  clearAllData: () => void;

  settings: Settings;
  setSettings: (patch: Partial<Settings>) => void;

  server: ServerState;
  /** Asks the server again about itself, its workspaces, profiles, placeholders and saved requests; resolves when the answer is in. */
  refreshServer: () => Promise<void>;
  /** Set to open the Placeholders page on one placeholder; that page clears it. */
  placeholderIntent: PlaceholderIntent | null;
  setPlaceholderIntent: (intent: PlaceholderIntent | null) => void;

  // ---- workspaces ----
  /** The workspace that is open, or `null` (a server without workspaces, or none created yet). */
  workspace: string | null;
  /** Whether the server uses workspaces, once it has answered. */
  workspacesEnabled: boolean;
  switchWorkspace: (name: string) => Promise<void>;
  createWorkspace: (name: string, description: string) => Promise<WorkspaceSummary>;
  /** Sets a workspace aside; resolves to the new name of its folder. */
  removeWorkspace: (name: string) => Promise<string>;
  /** Where the request being edited came from, if it was opened from a saved request of the workspace. */
  origin: SavedOrigin | null;
  /** Saves the request into the workspace (a new one, or over its origin); resolves to what was left out of it. */
  saveShared: (options: { name: string; folder: string; asNew: boolean }) => Promise<string[]>;
  openShared: (id: string) => Promise<void>;
  removeShared: (id: string, revision: string) => Promise<void>;
  /** Moves a request saved in this browser into the workspace; resolves to what was left out of it. */
  moveToWorkspace: (id: string) => Promise<string[]>;
}

const AppContext = createContext<AppState | null>(null);

function newDraft(settings: Settings): RequestDraft {
  const draft = emptyDraft();
  draft.body = SAMPLE_BODY;
  draft.options.timeoutSeconds = settings.timeoutSeconds;
  draft.options.followRedirects = settings.followRedirects;
  return draft;
}

function initialDraft(settings: Settings, key: string): RequestDraft {
  const stored = loadJson<Partial<StoredDraft> | undefined>(key, undefined);
  return stored && typeof stored.body === "string" ? fromStored(stored) : newDraft(settings);
}

/** The workspace to start in: the one a link names, else the one that was open last. The server has the last word. */
function initialWorkspace(): string | null {
  const fromLink = parseRoute(window.location.hash).workspace;
  if (fromLink) return fromLink;
  const stored = loadJson<unknown>(KEYS.workspace, null);
  return typeof stored === "string" && WORKSPACE_NAME.test(stored) ? stored : null;
}

function toUiError(e: unknown): UiError {
  if (e instanceof ServerError) return { kind: "server", message: e.message, category: e.category };
  if (e instanceof DOMException && e.name === "AbortError") return { kind: "cancelled", message: "The request was cancelled." };
  return { kind: "server", message: e instanceof Error ? e.message : String(e) };
}

const NO_SERVER: ServerState = { info: null, loaded: false, profiles: null, placeholders: null, library: null, workspaces: null };

export function AppProvider({ children }: { children: ReactNode }) {
  const [page, setPage] = useState<Page>(() => parseRoute(window.location.hash).page);
  const [workspace, setWorkspaceState] = useState<string | null>(initialWorkspace);
  const [settings, setSettingsState] = useState<Settings>(loadSettings);
  const [draft, setDraft] = useState<RequestDraft>(() => initialDraft(settings, scopedKeys(workspace).draft));
  const [response, setResponse] = useState<ResponseView | null>(null);
  const [error, setError] = useState<UiError | null>(null);
  const [notice, setNotice] = useState<Notice | null>(null);
  const [sending, setSending] = useState(false);
  const [attachmentLoad, setAttachmentLoad] = useState<AttachmentLoad | null>(null);
  const [serverPreview, setServerPreview] = useState<RequestView | null>(null);
  const [history, setHistory] = useState<HistoryEntry[]>(() => loadJson<HistoryEntry[]>(scopedKeys(workspace).history, []));
  const [saved, setSaved] = useState<SavedRequest[]>(() => loadJson<SavedRequest[]>(KEYS.saved, []));
  const [server, setServer] = useState<ServerState>(NO_SERVER);
  const [origin, setOrigin] = useState<SavedOrigin | null>(null);
  const [placeholderIntent, setPlaceholderIntent] = useState<PlaceholderIntent | null>(null);
  const controller = useRef<AbortController | null>(null);

  // What the callbacks below need to read without being made again every time it changes.
  const workspaceRef = useRef(workspace);
  const draftRef = useRef(draft);
  const settingsRef = useRef(settings);
  const pageRef = useRef(page);
  const serverRef = useRef(server);
  const refreshSeq = useRef(0);
  draftRef.current = draft;
  settingsRef.current = settings;
  pageRef.current = page;
  serverRef.current = server;

  // ---- navigation (hash based, so the server needs no routing) --------------------------------------------------
  const navigate = useCallback((next: Page) => {
    window.location.hash = buildHash({ page: next, workspace: workspaceRef.current });
    setPage(next);
  }, []);
  /** Shows the page and workspace in the address, without making a history entry of it. */
  const replaceHash = useCallback((next: Page, name: string | null) => {
    try {
      window.history.replaceState(null, "", buildHash({ page: next, workspace: name }));
    } catch {
      /* the address stays as it is */
    }
  }, []);

  // ---- persistence ----------------------------------------------------------------------------------------------
  useEffect(() => {
    const t = setTimeout(() => saveJson(scopedKeys(workspace).draft, toStored(draft)), 400);
    return () => clearTimeout(t);
  }, [draft, workspace]);
  useEffect(() => void saveJson(scopedKeys(workspace).history, history), [history, workspace]);
  useEffect(() => void saveJson(KEYS.saved, saved), [saved]);
  useEffect(() => void saveJson(KEYS.settings, settings), [settings]);

  const clearResult = useCallback(() => {
    setResponse(null);
    setError(null);
    setServerPreview(null);
  }, []);

  // ---- the workspace that is open -------------------------------------------------------------------------------
  /**
   * Makes `next` the open workspace. The request being edited and the history belong to a workspace, so they are swapped
   * with it in one go: the ones of the workspace being left are saved first, and the ones of the new one are read.
   */
  const enterWorkspace = useCallback((next: string | null) => {
    const previous = workspaceRef.current;
    if (next === previous) return;
    saveJson(scopedKeys(previous).draft, toStored(draftRef.current));
    workspaceRef.current = next;
    setApiWorkspace(next);
    const keys = scopedKeys(next);
    setWorkspaceState(next);
    setDraft(initialDraft(settingsRef.current, keys.draft));
    setHistory(loadJson<HistoryEntry[]>(keys.history, []));
    setOrigin(null);
    setNotice(null);
    setResponse(null);
    setError(null);
    setServerPreview(null);
    setAttachmentLoad(null);
    // What was read from the workspace being left must not be shown as the new one's while its own is on the way.
    setServer((s) => ({ ...s, profiles: null, placeholders: null, library: null }));
    if (next) saveJson(KEYS.workspace, next);
    replaceHash(pageRef.current, next);
  }, [replaceHash]);

  // ---- the SoapCLI server this page came from -------------------------------------------------------------------
  const refreshServer = useCallback(async () => {
    const seq = ++refreshSeq.current;
    const stale = () => seq !== refreshSeq.current; // a newer refresh (another workspace opened, say) has taken over
    setApiWorkspace(workspaceRef.current);
    const info = await fetchServerInfo();
    if (!info) {
      if (!stale()) setServer({ ...NO_SERVER, loaded: true });
      return;
    }
    let workspaces: ServerWorkspaces | null = null;
    let active: string | null = null;
    if (info.workspacesEnabled) {
      workspaces = await fetchWorkspaces().catch(() => null);
      const names = workspaces?.workspaces.map((w) => w.name) ?? [];
      const wanted = workspaceRef.current;
      active = wanted && names.includes(wanted) ? wanted : names[0] ?? null;
    }
    if (stale()) return;
    if (active !== workspaceRef.current) enterWorkspace(active);
    if (info.workspacesEnabled && !active) {
      setServer({ info, loaded: true, profiles: null, placeholders: null, library: null, workspaces });
      return;
    }
    const [own, profiles, placeholders, library] = await Promise.all([
      info.workspacesEnabled ? fetchWorkspaceInfo().catch(() => null) : Promise.resolve(null),
      fetchServerProfiles().catch(() => null),
      fetchPlaceholders().catch(() => null),
      info.workspacesEnabled ? fetchLibrary().catch(() => null) : Promise.resolve(null),
    ]);
    if (stale()) return;
    setServer({ info: own ? { ...info, ...own } : info, loaded: true, profiles, placeholders, library, workspaces });
  }, [enterWorkspace]);

  const switchWorkspace = useCallback(async (name: string) => {
    enterWorkspace(name);
    await refreshServer();
  }, [enterWorkspace, refreshServer]);

  // ---- draft ----------------------------------------------------------------------------------------------------
  const patch = useCallback((p: Partial<RequestDraft>) => setDraft((d) => ({ ...d, ...p })), []);
  const update = useCallback((fn: (d: RequestDraft) => RequestDraft) => setDraft(fn), []);

  /** Puts a stored request in the editor; a profile that is not in this workspace is dropped, and the person is told. */
  const load = useCallback((stored: Partial<StoredDraft>, from: SavedOrigin | null) => {
    const next = fromStored(stored);
    const profiles = serverRef.current.profiles;
    let told: Notice | null = null;
    if (next.profile && profiles && !profiles.profiles.some((p) => p.name === next.profile)) {
      told = { kind: "info", text: `This request used the profile “${next.profile}”, which this ${workspaceRef.current ? "workspace does" : "server does"} not have, so none is selected.` };
      next.profile = "";
    }
    setDraft(next);
    setOrigin(from);
    setNotice(told);
    clearResult();
  }, [clearResult]);
  const loadStored = useCallback((stored: StoredDraft) => load(stored, null), [load]);
  const resetDraft = useCallback(() => {
    setDraft(newDraft(settings));
    setOrigin(null);
    setNotice(null);
    clearResult();
  }, [clearResult, settings]);
  const dismissNotice = useCallback(() => setNotice(null), []);

  // ---- sending --------------------------------------------------------------------------------------------------
  const send = useCallback(async () => {
    const d = draft;
    controller.current?.abort();
    const ctl = new AbortController();
    controller.current = ctl;
    setSending(true);
    setError(null);
    setAttachmentLoad(null);
    const record = (extra: Partial<HistoryEntry>) =>
      setHistory((h) => [{ id: newId(), at: Date.now(), draft: toStored(d), ...extra }, ...h].slice(0, HISTORY_LIMIT));
    try {
      // Files named by path are loaded first, one by one, with a dialog; the request goes out only if all of them load.
      const files = pathAttachments(d);
      if (files.length > 0) {
        const opened = Date.now();
        setAttachmentLoad({ items: pendingItems(files), status: "running" });
        const loaded = await loadAttachments(files, checkAttachment, (items) => setAttachmentLoad({ items, status: "running" }), ctl.signal);
        if (!loaded.ok) {
          setAttachmentLoad({ items: loaded.items, status: "failed" });
          return;
        }
        await pause(Math.max(0, MIN_VISIBLE_MS - (Date.now() - opened)), ctl.signal);
        setAttachmentLoad(null);
      }
      const view = await sendViaServer(d, ctl.signal);
      setResponse(view);
      record({ status: view.status, statusText: view.statusText, elapsedMs: view.elapsedMs });
    } catch (e) {
      setAttachmentLoad(null);
      const ui = toUiError(e);
      setError(ui);
      setResponse(null);
      if (ui.kind !== "cancelled") record({ error: ui.message });
    } finally {
      if (controller.current === ctl) setSending(false);
    }
  }, [draft]);
  const cancel = useCallback(() => controller.current?.abort(), []);
  const dismissAttachmentLoad = useCallback(() => setAttachmentLoad(null), []);

  const refreshServerPreview = useCallback(async () => {
    try {
      setServerPreview(await previewViaServer(draft));
      setError(null);
    } catch (e) {
      setError(toUiError(e));
    }
  }, [draft]);

  // ---- requests saved in this browser ---------------------------------------------------------------------------
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

  // ---- the library of the workspace -----------------------------------------------------------------------------
  const refreshLibrary = useCallback(async () => {
    const library = await fetchLibrary().catch(() => null);
    if (library) setServer((s) => ({ ...s, library }));
  }, []);

  const saveShared = useCallback(async ({ name, folder, asNew }: { name: string; folder: string; asNew: boolean }) => {
    const target = asNew ? null : origin;
    const result = await saveSavedRequest({
      ...(target ? { id: target.id, revision: target.revision } : {}),
      name: name.trim(), folder: folder.trim(), draft: toStored(draft),
    });
    setOrigin({ id: result.id, name: name.trim(), folder: folder.trim(), revision: result.revision });
    await refreshLibrary();
    return result.warnings;
  }, [draft, origin, refreshLibrary]);

  const openShared = useCallback(async (id: string) => {
    try {
      const detail = await fetchSavedRequest(id);
      load(detail.draft, { id: detail.id, name: detail.name, folder: detail.folder ?? "", revision: detail.revision });
      navigate("request");
    } catch (e) {
      setNotice({ kind: "error", text: e instanceof Error ? e.message : String(e) });
      navigate("request");
    }
  }, [load, navigate]);

  const removeShared = useCallback(async (id: string, revision: string) => {
    await deleteSavedRequest(id, revision);
    setOrigin((o) => (o?.id === id ? null : o));
    await refreshLibrary();
  }, [refreshLibrary]);

  const moveToWorkspace = useCallback(async (id: string) => {
    const mine = saved.find((s) => s.id === id);
    if (!mine) return [];
    const result = await saveSavedRequest({ name: mine.name, folder: "", draft: mine.draft });
    setSaved((s) => s.filter((x) => x.id !== id));
    await refreshLibrary();
    return result.warnings;
  }, [saved, refreshLibrary]);

  // ---- managing the workspaces ----------------------------------------------------------------------------------
  const createWorkspaceAndOpen = useCallback(async (name: string, description: string) => {
    const created = await createWorkspace({ name, description });
    await switchWorkspace(created.name);
    return created;
  }, [switchWorkspace]);

  const removeWorkspaceByName = useCallback(async (name: string) => {
    const movedTo = await removeWorkspace(name);
    removeKey(scopedKeys(name).draft);
    removeKey(scopedKeys(name).history);
    await refreshServer(); // if it was the open one, the first of the others takes its place
    return movedTo;
  }, [refreshServer]);

  const setSettings = useCallback((p: Partial<Settings>) => setSettingsState((s) => ({ ...s, ...p })), []);

  // ---- start up: the server, and a link that names a saved request ------------------------------------------------
  useEffect(() => {
    const route: Route = parseRoute(window.location.hash);
    void refreshServer().then(() => {
      if (route.open) void openShared(route.open);
    });
    const onHash = () => {
      const next = parseRoute(window.location.hash);
      setPage(next.page);
      if (next.workspace && next.workspace !== workspaceRef.current) {
        void switchWorkspace(next.workspace).then(async () => {
          if (next.open) await openShared(next.open);
        });
      } else if (next.open) {
        void openShared(next.open);
      }
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
    // Once: it reads the link the page was opened with, and the later ones as they come.
  }, []);

  const workspacesEnabled = server.info?.workspacesEnabled === true;

  const value = useMemo<AppState>(() => ({
    page, navigate, draft, patch, update, loadStored, resetDraft, response, error, sending, send, cancel, clearResult,
    attachmentLoad, dismissAttachmentLoad, serverPreview, refreshServerPreview, notice, dismissNotice, history, clearHistory,
    removeHistory, saved, saveCurrent, removeSaved, exportLibrary, importLibrary, clearAllData, settings, setSettings, server,
    refreshServer, placeholderIntent, setPlaceholderIntent, workspace, workspacesEnabled, switchWorkspace,
    createWorkspace: createWorkspaceAndOpen, removeWorkspace: removeWorkspaceByName, origin, saveShared, openShared, removeShared,
    moveToWorkspace,
  }), [page, navigate, draft, patch, update, loadStored, resetDraft, response, error, sending, send, cancel, clearResult,
    attachmentLoad, dismissAttachmentLoad, serverPreview, refreshServerPreview, notice, dismissNotice, history, clearHistory,
    removeHistory, saved, saveCurrent, removeSaved, exportLibrary, importLibrary, clearAllData, settings, setSettings, server,
    refreshServer, placeholderIntent, workspace, workspacesEnabled, switchWorkspace, createWorkspaceAndOpen,
    removeWorkspaceByName, origin, saveShared, openShared, removeShared, moveToWorkspace]);

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

export function useApp(): AppState {
  const ctx = useContext(AppContext);
  if (!ctx) throw new Error("useApp must be used inside AppProvider");
  return ctx;
}
