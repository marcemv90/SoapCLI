import { emptyDraft, newId, type RequestDraft } from "./types";

/** localStorage can be unavailable (private windows, blocked site data): every access is guarded. */
export function loadJson<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

export function saveJson(key: string, value: unknown): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    return false;
  }
}

export function removeKey(key: string): void {
  try {
    localStorage.removeItem(key);
  } catch {
    /* nothing to do */
  }
}

export const KEYS = {
  draft: "soapcli.draft",
  history: "soapcli.history",
  saved: "soapcli.saved",
  settings: "soapcli.settings",
  theme: "soapcli.theme",
} as const;

/** An attachment as stored: only the path on the server. A file chosen on this computer is never stored. */
export interface StoredAttachment {
  cid: string;
  path: string;
}

/** A draft as stored: never the password or token, and never the contents of a file. */
export type StoredDraft = Omit<RequestDraft, "attachments" | "auth"> & {
  auth: { kind: RequestDraft["auth"]["kind"]; user: string };
  /** Absent in what older versions stored. */
  attachments?: StoredAttachment[];
};

export function toStored(draft: RequestDraft): StoredDraft {
  const { attachments, auth, ...rest } = draft;
  const paths: StoredAttachment[] = [];
  for (const a of attachments) if (a.kind === "path" && a.path.trim()) paths.push({ cid: a.cid, path: a.path });
  return { ...rest, auth: { kind: auth.kind, user: auth.user }, attachments: paths };
}

/** Reads a draft back, picking only the fields that exist today (older versions stored a few more). */
export function fromStored(stored: Partial<StoredDraft> | undefined): RequestDraft {
  const base = emptyDraft();
  if (!stored) return base;
  return {
    ...base,
    endpoint: typeof stored.endpoint === "string" ? stored.endpoint : base.endpoint,
    body: typeof stored.body === "string" ? stored.body : base.body,
    soapVersion: stored.soapVersion ?? base.soapVersion,
    soapAction: typeof stored.soapAction === "string" ? stored.soapAction : base.soapAction,
    headers: (stored.headers ?? []).map((h) => ({ ...h, id: h.id || newId() })),
    args: stored.args ?? {},
    mtom: stored.mtom === true,
    options: { ...base.options, ...(stored.options ?? {}) },
    auth: { ...base.auth, kind: stored.auth?.kind ?? "none", user: stored.auth?.user ?? "" },
    attachments: (Array.isArray(stored.attachments) ? stored.attachments : [])
      .filter((a): a is StoredAttachment => !!a && typeof a.cid === "string" && typeof a.path === "string" && a.path.trim() !== "")
      .map((a) => ({ kind: "path" as const, id: newId(), cid: a.cid, path: a.path })),
    profile: typeof stored.profile === "string" ? stored.profile : "",
  };
}

export interface HistoryEntry {
  id: string;
  at: number;
  draft: StoredDraft;
  status?: number;
  statusText?: string;
  elapsedMs?: number;
  error?: string;
}

export interface SavedRequest {
  id: string;
  name: string;
  savedAt: number;
  draft: StoredDraft;
}

/** How the request page lays out the request and the response. */
export type RequestLayout = "side" | "stacked";

export interface Settings {
  timeoutSeconds: number;
  followRedirects: boolean;
  requestLayout: RequestLayout;
}

export const DEFAULT_SETTINGS: Settings = { timeoutSeconds: 60, followRedirects: false, requestLayout: "side" };

/** The saved settings over the defaults; anything else in storage (an option that no longer exists) is dropped. */
export function loadSettings(): Settings {
  const stored = loadJson<Partial<Settings>>(KEYS.settings, {});
  return {
    timeoutSeconds: typeof stored.timeoutSeconds === "number" ? stored.timeoutSeconds : DEFAULT_SETTINGS.timeoutSeconds,
    followRedirects: typeof stored.followRedirects === "boolean" ? stored.followRedirects : DEFAULT_SETTINGS.followRedirects,
    requestLayout: stored.requestLayout === "stacked" ? "stacked" : "side",
  };
}

export const HISTORY_LIMIT = 50;
