import { activeAttachments } from "../attachments";
import { bytesToBase64 } from "../format";
import type { StoredDraft } from "../storage";
import type { HeaderPair, RequestDraft, RequestView, ResponseView } from "../types";

/** A failure of the SoapCLI server or of a call it made for us. */
export class ServerError extends Error {
  constructor(
    readonly category: string,
    message: string,
    readonly status: number,
  ) {
    super(message);
  }
}

export interface ServerInfo {
  version: string;
  insecureTlsAllowed: boolean;
  allowedHosts: string[];
  maxBodyBytes: number;
  profilesFileFound: boolean;
  profilesFile?: string;
  /** The only folder a request may attach files from, by path. */
  attachmentsDir: string;
  placeholdersFile: string;
  /** Whether the server lets the UI change the profiles and placeholders files (they may still be read-only). */
  configEditAllowed: boolean;
  /** Whether the server uses workspaces: then the files above are those of the workspace asked about. */
  workspacesEnabled: boolean;
  workspacesDir?: string;
  workspace?: WorkspaceSummary;
}

/** A folder with its own profiles, placeholder options, attachments and saved requests. */
export interface WorkspaceSummary {
  name: string;
  description?: string;
  /** An administrator marked it read-only: it can be used, not changed. */
  readOnly: boolean;
  /** Destinations it is limited to, on top of what the server allows. */
  allowedHosts: string[];
  /** A problem with its workspace.json. */
  error?: string;
  /** How many it has; absent when they cannot be counted. */
  profiles?: number;
  placeholders?: number;
  requests?: number;
}

export interface ServerWorkspaces {
  enabled: boolean;
  dir?: string;
  /** Whether the UI may create and remove workspaces, and why not. */
  manageable: boolean;
  manageReason?: string;
  workspaces: WorkspaceSummary[];
}

/** A saved request of a workspace, as listed: without its body. */
export interface SavedRequestSummary {
  id: string;
  name: string;
  folder?: string;
  /** ISO 8601. */
  updatedAt?: string;
  endpoint?: string;
  soapAction?: string;
  profile?: string;
  revision?: string;
  /** Why the file cannot be used as a saved request. */
  error?: string;
}

export interface ServerLibrary {
  editable: boolean;
  readOnlyReason?: string;
  requests: SavedRequestSummary[];
}

export interface SavedRequestDetail {
  id: string;
  name: string;
  folder?: string;
  updatedAt?: string;
  revision: string;
  draft: Partial<StoredDraft>;
}

export interface SaveRequestResult {
  id: string;
  revision: string;
  updatedAt: string;
  /** What was left out of the saved request, such as the value of a header that holds a credential. */
  warnings: string[];
}

/** What the server found at an attachment path. */
export interface AttachmentCheck {
  path: string;
  name: string;
  size: number;
  /** Where the file really is. */
  resolved: string;
}

export interface ServerProfile {
  name: string;
  settings?: Record<string, unknown>;
  error?: string;
  hasRequestFile: boolean;
}

export interface ServerProfiles {
  file: string;
  found: boolean;
  error?: string;
  /** Identifies the content of the file: a change has to quote the one it was made from. */
  revision: string;
  editable: boolean;
  readOnlyReason?: string;
  profiles: ServerProfile[];
}

/** A profile as it is in the file, not merged with what it extends, with secrets shown as `****`. */
export interface ProfileDefinition {
  name: string;
  revision: string;
  definition: Record<string, unknown>;
  /** The profiles that inherit from this one. */
  dependents: string[];
}

export interface SaveResult {
  revision: string;
  warnings: string[];
}

export interface PlaceholderOption {
  value: string;
  /** What the dropdown shows; the value itself when absent. */
  label?: string;
}

export interface PlaceholderEntry {
  name: string;
  description?: string;
  options: PlaceholderOption[];
  error?: string;
}

export interface ServerPlaceholders {
  file: string;
  found: boolean;
  error?: string;
  revision: string;
  editable: boolean;
  readOnlyReason?: string;
  placeholders: PlaceholderEntry[];
}

interface ApiNameValue {
  name: string;
  value: string;
}

interface ApiRequestInfo {
  endpoint: string;
  soapVersion: string;
  headers: ApiNameValue[];
  contentLength: number;
  mtom: boolean;
  previewXml: string;
  attachments: string[];
  warnings: string[];
}

interface ApiSendResponse {
  status: number;
  statusMessage: string;
  elapsedMillis: number;
  uri: string;
  contentType?: string;
  bodySize: number;
  binary: boolean;
  body?: string;
  rawBody?: string;
  bodyBase64?: string;
  headers: ApiNameValue[];
  location?: string;
  request: ApiRequestInfo;
}

/** The workspace the calls that belong to one are made for; set by the page when it opens one. */
let workspace: string | null = null;

export function setWorkspace(name: string | null): void {
  workspace = name;
}

/** `profiles` of this server, or `workspaces/billing/profiles` of one of its workspaces. */
const scoped = (path: string) => (workspace === null ? path : `workspaces/${encodeURIComponent(workspace)}/${path}`);

// Relative URLs, so the UI also works when it is served under a path prefix. Everything belongs to the workspace that is
// open, except what is about the whole server (`global`).
async function api<T>(path: string, init?: RequestInit & { json?: unknown; global?: boolean }): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`api/${init?.global ? path : scoped(path)}`, {
      method: init?.json === undefined ? init?.method ?? "GET" : "POST",
      headers: init?.json === undefined ? undefined : { "Content-Type": "application/json" },
      body: init?.json === undefined ? undefined : JSON.stringify(init.json),
      signal: init?.signal,
      cache: "no-store",
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw new ServerError("unavailable", "Cannot reach the SoapCLI server this page came from.", 0);
  }
  const text = await response.text();
  if (!response.ok) {
    let category = "internal";
    let message = `The server answered HTTP ${response.status}.`;
    try {
      const parsed = JSON.parse(text) as { error?: { category?: string; message?: string } };
      if (parsed.error) {
        category = parsed.error.category ?? category;
        message = parsed.error.message ?? message;
      }
    } catch {
      /* not JSON: keep the generic message */
    }
    throw new ServerError(category, message, response.status);
  }
  return (text ? JSON.parse(text) : undefined) as T;
}

/** `null` when this page is not served by a SoapCLI server (a static copy, for instance). */
export async function fetchServerInfo(): Promise<ServerInfo | null> {
  try {
    return await api<ServerInfo>("info", { global: true });
  } catch {
    return null;
  }
}

/** The info of the workspace that is open: where its files are. */
export const fetchWorkspaceInfo = () => api<ServerInfo>("info");

export const fetchWorkspaces = () => api<ServerWorkspaces>("workspaces", { global: true });

export const createWorkspace = (body: { name: string; description: string }) =>
  api<WorkspaceSummary>("workspaces/create", { json: body, global: true });

/** Sets a workspace aside (its folder is renamed, nothing is deleted). Resolves to the new name of the folder. */
export const removeWorkspace = async (name: string) =>
  (await api<{ movedTo: string }>("workspaces/remove", { json: { name }, global: true })).movedTo;

export const fetchLibrary = () => api<ServerLibrary>("requests");
export const fetchSavedRequest = (id: string) => api<SavedRequestDetail>(`requests/${encodeURIComponent(id)}`);

/** Saves a new request, or (with `id` and the `revision` it was opened at) a change to one. */
export const saveSavedRequest = (body: { id?: string; revision?: string; name: string; folder: string; draft: StoredDraft }) =>
  api<SaveRequestResult>("requests/save", { json: body });

export const deleteSavedRequest = (id: string, revision: string) => api<Record<string, never>>("requests/delete", { json: { id, revision } });

export const fetchServerProfiles = () => api<ServerProfiles>("profiles");
export const fetchPlaceholders = () => api<ServerPlaceholders>("placeholders");

export const fetchProfileDefinition = (name: string) =>
  api<ProfileDefinition>(`profiles/${encodeURIComponent(name)}/definition`);

/** Creates a profile, or replaces its definition. A secret left as `****` keeps the saved value. */
export const saveProfile = (body: { name: string; revision: string; create: boolean; definition: Record<string, unknown> }) =>
  api<SaveResult>("profiles/save", { json: body });

export const deleteProfile = (name: string, revision: string) => api<SaveResult>("profiles/delete", { json: { name, revision } });

export const savePlaceholder = (body: { name: string; revision: string; create: boolean; description: string; options: PlaceholderOption[] }) =>
  api<SaveResult>("placeholders/save", { json: body });

export const deletePlaceholder = (name: string, revision: string) =>
  api<SaveResult>("placeholders/delete", { json: { name, revision } });

export async function fetchProfileRequest(name: string): Promise<string> {
  let response: Response;
  try {
    response = await fetch(`api/${scoped(`profiles/${encodeURIComponent(name)}/request`)}`, { cache: "no-store" });
  } catch {
    throw new ServerError("unavailable", "Cannot reach the SoapCLI server this page came from.", 0);
  }
  if (!response.ok) throw new ServerError("config", `The profile '${name}' has no request file.`, response.status);
  return response.text();
}

/** The JSON the server expects for a draft. Secrets travel only to the server the page came from. Only the files
 * the body refers to with `cid:` are included: by path (the server reads them) or as Base64 (chosen on this computer). */
export function toServerRequest(draft: RequestDraft): Record<string, unknown> {
  const headers: Record<string, string> = {};
  for (const h of draft.headers) if (h.enabled && h.name.trim()) headers[h.name.trim()] = h.value;
  return {
    profile: draft.profile || undefined,
    endpoint: draft.endpoint.trim() || undefined,
    body: draft.body,
    soapVersion: draft.soapVersion,
    soapAction: draft.soapAction.trim() || undefined,
    headers,
    args: draft.args,
    authBasic: draft.auth.kind === "basic" && (draft.auth.user || draft.auth.password)
      ? `${draft.auth.user}:${draft.auth.password}` : undefined,
    authBearer: draft.auth.kind === "bearer" && draft.auth.token.trim() ? draft.auth.token.trim() : undefined,
    timeoutSeconds: draft.options.timeoutSeconds,
    followRedirects: draft.options.followRedirects,
    insecure: draft.options.insecure || undefined,
    mtom: draft.mtom,
    attachments: activeAttachments(draft).map((a) => a.kind === "path"
      ? { cid: a.cid, path: a.path.trim() }
      : { cid: a.cid, filename: a.filename, contentBase64: bytesToBase64(a.data) }),
  };
}

const pairs = (list: ApiNameValue[]): HeaderPair[] => list.map((h) => [h.name, h.value]);

function toRequestView(info: ApiRequestInfo): RequestView {
  return {
    url: info.endpoint,
    soapVersion: info.soapVersion,
    headers: pairs(info.headers),
    contentLength: info.contentLength,
    mtom: info.mtom,
    previewXml: info.previewXml,
    attachments: info.attachments,
    warnings: info.warnings,
  };
}

export async function sendViaServer(draft: RequestDraft, signal?: AbortSignal): Promise<ResponseView> {
  const r = await api<ApiSendResponse>("send", { json: toServerRequest(draft), signal });
  return {
    status: r.status,
    statusText: r.statusMessage,
    elapsedMs: r.elapsedMillis,
    url: r.uri,
    contentType: r.contentType ?? null,
    size: r.bodySize,
    headers: pairs(r.headers),
    body: r.body ?? "",
    rawBody: r.rawBody,
    binary: r.binary,
    bodyBase64: r.bodyBase64,
    redirectLocation: r.location,
    request: toRequestView(r.request),
  };
}

export async function previewViaServer(draft: RequestDraft): Promise<RequestView> {
  return toRequestView(await api<ApiRequestInfo>("preview", { json: toServerRequest(draft) }));
}

/** Asks the server to open the file at `path` (inside its attachments folder) and say what it found. */
export const checkAttachment = (path: string, signal?: AbortSignal) =>
  api<AttachmentCheck>("attachments/check", { json: { path }, signal });

export async function formatViaServer(xml: string): Promise<string> {
  return (await api<{ xml: string }>("format", { json: { xml }, global: true })).xml;
}
