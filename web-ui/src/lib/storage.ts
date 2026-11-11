import { emptyDraft, newId, type RequestDraft, type Transport } from "./types";

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

/** A draft as stored: never the password or token, and never the attachments' bytes. */
export type StoredDraft = Omit<RequestDraft, "attachments" | "auth"> & {
  auth: { kind: RequestDraft["auth"]["kind"]; user: string };
};

export function toStored(draft: RequestDraft): StoredDraft {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const { attachments, auth, ...rest } = draft;
  return { ...rest, auth: { kind: auth.kind, user: auth.user } };
}

export function fromStored(stored: Partial<StoredDraft> | undefined): RequestDraft {
  const base = emptyDraft();
  if (!stored) return base;
  return {
    ...base,
    ...stored,
    headers: (stored.headers ?? []).map((h) => ({ ...h, id: h.id || newId() })),
    args: stored.args ?? {},
    options: { ...base.options, ...(stored.options ?? {}) },
    auth: { ...base.auth, kind: stored.auth?.kind ?? "none", user: stored.auth?.user ?? "" },
    attachments: [],
  };
}

export interface HistoryEntry {
  id: string;
  at: number;
  draft: StoredDraft;
  status?: number;
  statusText?: string;
  elapsedMs?: number;
  via: Transport;
  error?: string;
}

export interface SavedRequest {
  id: string;
  name: string;
  savedAt: number;
  draft: StoredDraft;
}

export interface Settings {
  defaultTransport: Transport;
  timeoutSeconds: number;
  followRedirects: boolean;
}

export const DEFAULT_SETTINGS: Settings = { defaultTransport: "browser", timeoutSeconds: 60, followRedirects: false };

export const HISTORY_LIMIT = 50;
