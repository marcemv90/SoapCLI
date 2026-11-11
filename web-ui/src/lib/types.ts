import type { SoapVersion } from "./soap";

export interface KeyValue {
  id: string;
  name: string;
  value: string;
  enabled: boolean;
}

export interface AttachmentInput {
  id: string;
  cid: string;
  filename: string;
  size: number;
  data: Uint8Array;
}

export type AuthKind = "none" | "basic" | "bearer";

/** Where the call is made from: this browser directly, or the SoapCLI server the page came from. */
export type Transport = "browser" | "server";

export interface RequestDraft {
  endpoint: string;
  body: string;
  soapVersion: "auto" | SoapVersion;
  soapAction: string;
  headers: KeyValue[];
  /** Values for ___NAME:default___ placeholders; only the ones the user filled in. */
  args: Record<string, string>;
  auth: { kind: AuthKind; user: string; password: string; token: string };
  attachments: AttachmentInput[];
  mtom: boolean;
  options: { timeoutSeconds: number; followRedirects: boolean; insecure: boolean };
  transport: Transport;
  /** Name of a profile on the SoapCLI server (server transport only), or "". */
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
    transport: "browser",
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
  via: Transport;
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
  /** The browser followed or hid a redirect and does not say where to. */
  opaqueRedirect?: boolean;
  /** Things worth knowing about how to read this response (for instance, hidden headers). */
  notes: string[];
  request: RequestView;
}
