import type { SoapVersion } from "./soap";

export interface KeyValue {
  id: string;
  name: string;
  value: string;
  enabled: boolean;
}

/**
 * The file for a `cid:` of the body: a path on the SoapCLI server, which is saved with the request and read by the
 * server when it is sent, or a file chosen on this computer, which only lives in memory.
 */
export type AttachmentInput =
  | { kind: "path"; id: string; cid: string; path: string }
  | { kind: "file"; id: string; cid: string; filename: string; size: number; data: Uint8Array };

export type AuthKind = "none" | "basic" | "bearer";

export interface RequestDraft {
  endpoint: string;
  body: string;
  soapVersion: "auto" | SoapVersion;
  soapAction: string;
  headers: KeyValue[];
  /** Values for ___NAME:default___ placeholders; only the ones the user filled in. */
  args: Record<string, string>;
  auth: { kind: AuthKind; user: string; password: string; token: string };
  /** The file for each `cid:` of the body (one per cid). Paths are saved with the request; file contents never are. */
  attachments: AttachmentInput[];
  mtom: boolean;
  options: { timeoutSeconds: number; followRedirects: boolean; insecure: boolean };
  /** Name of a profile on the SoapCLI server, or "". */
  profile: string;
}

let counter = 0;
export function newId(): string {
  counter += 1;
  return `${Date.now().toString(36)}-${counter.toString(36)}-${Math.random().toString(36).slice(2, 7)}`;
}

export function emptyDraft(): RequestDraft {
  return {
    endpoint: "",
    body: "",
    soapVersion: "auto",
    soapAction: "",
    headers: [],
    args: {},
    auth: { kind: "none", user: "", password: "", token: "" },
    attachments: [],
    mtom: false,
    options: { timeoutSeconds: 60, followRedirects: false, insecure: false },
    profile: "",
  };
}

export type HeaderPair = [name: string, value: string];

/** The request as it will be, or was, sent. Credentials are masked. */
export interface RequestView {
  url: string;
  soapVersion: SoapVersion | string;
  headers: HeaderPair[];
  contentLength: number;
  mtom: boolean;
  previewXml: string;
  attachments: string[];
  warnings: string[];
}

export interface ResponseView {
  status: number;
  statusText: string;
  elapsedMs: number;
  url: string;
  contentType: string | null;
  size: number;
  headers: HeaderPair[];
  /** Pretty-printed when XML. Empty for binary responses. */
  body: string;
  /** The body exactly as decoded, only when it differs from `body`. */
  rawBody?: string;
  binary: boolean;
  bodyBase64?: string;
  /** Where a redirect that was not followed pointed to. */
  redirectLocation?: string;
  request: RequestView;
}
