import { activeAttachments } from "./attachments";
import type { RequestDraft, RequestView } from "./types";

/** Quotes for a POSIX shell. */
export const shellQuote = (s: string) => `'${s.replace(/'/g, `'\\''`)}'`;

export type CommandKind = "jar" | "docker";

/** The equivalent `SoapCLI` command line. The request body is expected in `request.xml`. */
export function toCliCommand(draft: RequestDraft, kind: CommandKind, image = "ghcr.io/marcemv90/soapcli"): string {
  const args: string[] = [];
  if (draft.profile) args.push(`--profile ${shellQuote(draft.profile)}`);
  if (draft.endpoint.trim()) args.push(`--endpoint ${shellQuote(draft.endpoint.trim())}`);
  args.push("--request-file request.xml");
  if (draft.soapVersion !== "auto") args.push(`--soap-version ${draft.soapVersion}`);
  if (draft.soapAction.trim()) args.push(`--soap-action ${shellQuote(draft.soapAction.trim())}`);
  for (const h of draft.headers) {
    if (h.enabled && h.name.trim()) args.push(`--header ${shellQuote(`${h.name.trim()}: ${h.value}`)}`);
  }
  for (const [name, value] of Object.entries(draft.args)) args.push(`--arg ${shellQuote(`${name}=${value}`)}`);
  if (draft.auth.kind === "basic") args.push("--auth-basic-env SOAP_CREDENTIALS");
  if (draft.auth.kind === "bearer") args.push("--auth-bearer-env SOAP_TOKEN");
  for (const a of activeAttachments(draft)) {
    // A path is written as it is on the server; a file chosen here is assumed to sit next to request.xml.
    const file = a.kind === "path" ? a.path.trim() : `./${a.filename || a.cid}`;
    args.push(`--attachment ${shellQuote(`${a.cid}=${file}`)}`);
  }
  if (draft.mtom) args.push("--mtom");
  if (draft.options.timeoutSeconds > 0) args.push(`--timeout ${draft.options.timeoutSeconds}`);
  if (draft.options.followRedirects) args.push("--follow-redirects");
  if (draft.options.insecure) args.push("--insecure");

  const lines: string[] = [];
  if (draft.auth.kind === "basic") lines.push("# export SOAP_CREDENTIALS='user:password'");
  if (draft.auth.kind === "bearer") lines.push("# export SOAP_TOKEN='...'");
  const prefix = kind === "jar"
    ? "java -jar SoapCLI.jar"
    : `docker run --rm -v "$PWD:/work"${draft.auth.kind === "basic" ? " -e SOAP_CREDENTIALS" : ""}${draft.auth.kind === "bearer" ? " -e SOAP_TOKEN" : ""} ${image}`;
  lines.push([prefix, ...args].join(" \\\n  "));
  return lines.join("\n");
}

/**
 * A curl command that sends the same bytes, from the request as the server built it (`view`). Not offered for MTOM:
 * curl cannot rebuild the multipart message. The server masks credentials, so the values of the headers typed in the
 * form are taken from the form; whatever else is masked comes from a server profile and is left as `****` to be filled in.
 */
export function toCurl(draft: RequestDraft, view: Pick<RequestView, "url" | "headers" | "mtom">): string | null {
  if (view.mtom) return null;
  const own = new Map(draft.headers.filter((h) => h.enabled && h.name.trim()).map((h) => [h.name.trim().toLowerCase(), h.value]));
  const credentialsFromForm = draft.auth.kind !== "none";
  const parts = [`curl -sS -X POST ${shellQuote(view.url)}`];
  for (const [name, value] of view.headers) {
    const key = name.toLowerCase();
    if (key === "authorization" && credentialsFromForm) continue; // replaced below by the environment variable
    parts.push(`-H ${shellQuote(`${name}: ${own.get(key) ?? value}`)}`);
  }
  if (draft.auth.kind === "basic") parts.push(`-u "$SOAP_CREDENTIALS"`);
  if (draft.auth.kind === "bearer") parts.push(`-H "Authorization: Bearer $SOAP_TOKEN"`);
  if (draft.options.insecure) parts.push("-k");
  if (draft.options.followRedirects) parts.push("-L");
  if (draft.options.timeoutSeconds > 0) parts.push(`--max-time ${draft.options.timeoutSeconds}`);
  parts.push("--data-binary @request.xml");
  const note = parts.some((p) => p.includes("****")) ? "# Values shown as **** come from the server profile: fill them in\n" : "";
  return note + parts.join(" \\\n  ");
}
