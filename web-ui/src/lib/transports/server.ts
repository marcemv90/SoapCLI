import { bytesToBase64 } from "../charset";
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
  profiles: ServerProfile[];
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

// Relative URLs, so the UI also works when it is served under a path prefix.
async function api<T>(path: string, init?: RequestInit & { json?: unknown }): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`api/${path}`, {
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
    return await api<ServerInfo>("info");
  } catch {
    return null;
  }
}

export const fetchServerProfiles = () => api<ServerProfiles>("profiles");

export async function fetchProfileRequest(name: string): Promise<string> {
  let response: Response;
  try {
    response = await fetch(`api/profiles/${encodeURIComponent(name)}/request`, { cache: "no-store" });
  } catch {
    throw new ServerError("unavailable", "Cannot reach the SoapCLI server this page came from.", 0);
  }
  if (!response.ok) throw new ServerError("config", `The profile '${name}' has no request file.`, response.status);
  return response.text();
}

/** The JSON the server expects for a draft. Secrets travel only to the server the page came from. */
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
    attachments: draft.attachments
      .filter((a) => a.cid.trim())
      .map((a) => ({ cid: a.cid.trim(), filename: a.filename, contentBase64: bytesToBase64(a.data) })),
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
    via: "server",
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
    notes: [],
    request: toRequestView(r.request),
  };
}

export async function previewViaServer(draft: RequestDraft): Promise<RequestView> {
  return toRequestView(await api<ApiRequestInfo>("preview", { json: toServerRequest(draft) }));
}

export async function formatViaServer(xml: string): Promise<string> {
  return (await api<{ xml: string }>("format", { json: { xml } })).xml;
}
