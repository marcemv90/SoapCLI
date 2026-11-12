import { useEffect, useState } from "react";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { Switch } from "@/components/ui/switch";
import { AlertIcon, ChevronDownIcon, CodeIcon, ColumnsIcon, DownloadIcon, FloppyDiscIcon, GlobeIcon, RowsIcon, SendIcon, XIcon } from "@/icons";
import { activeAttachments } from "@/lib/attachments";
import { toCliCommand, toCurl } from "@/lib/command";
import { copyText, downloadFile } from "@/lib/format";
import { findPlaceholders } from "@/lib/placeholders";
import type { SoapVersion } from "@/lib/soap";
import type { RequestLayout } from "@/lib/storage";
import { previewViaServer } from "@/lib/transports/server";
import { useApp } from "@/state/app";
import { Kbd, NativeSelect, Segmented } from "@/components/common/controls";

function CopyAsMenu() {
  const { draft, workspace, server } = useApp();
  const inWorkspace = workspace ? { name: workspace, dir: server.info?.workspacesDir } : undefined;
  const [message, setMessage] = useState<string | null>(null);

  const done = (text: string) => {
    setMessage(text);
    setTimeout(() => setMessage(null), 2000);
  };
  const copy = async (text: string, what: string) => done((await copyText(text)) ? `${what} copied` : "Could not copy");

  // The server builds the request (headers, version, endpoint of a profile...), so it is asked for it.
  const curl = async () => {
    try {
      const body = toCurl(draft, await previewViaServer(draft));
      if (body === null) return done("curl cannot send MTOM; use the CLI command");
      const notes = [];
      if (findPlaceholders(draft.body).length > 0) notes.push("# request.xml must hold the final XML: replace the ___NAME:default___ markers first\n");
      if (activeAttachments(draft).length > 0) notes.push("# ...and each cid:NAME with the Base64 of its file (curl cannot attach files by itself)\n");
      await copy(notes.join("") + body, "curl command");
    } catch (e) {
      done(e instanceof Error ? e.message : "Cannot build the request");
    }
  };

  return (
    <div className="flex items-center gap-2">
      {message && <span role="status" className="text-xs text-gray-500 dark:text-gray-400">{message}</span>}
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button variant="outline" size="sm" />}>
          <CodeIcon /> Copy as <ChevronDownIcon className="size-4" />
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-72 p-2">
          <DropdownMenuItem onClick={() => void copy(toCliCommand(draft, "jar", undefined, inWorkspace), "CLI command")}>
            <CodeIcon className="size-5" /> SoapCLI command (java -jar)
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => void copy(toCliCommand(draft, "docker", undefined, inWorkspace), "Docker command")}>
            <CodeIcon className="size-5" /> SoapCLI command (Docker)
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => void curl()}>
            <CodeIcon className="size-5" /> curl command
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem onClick={() => downloadFile("request.xml", draft.body, "application/xml")}>
            <DownloadIcon className="size-5" /> Download request.xml
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </div>
  );
}

type SaveTarget = "workspace" | "browser";

function SaveDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { draft, saveCurrent, saveShared, workspace, workspacesEnabled, server, origin } = useApp();
  const library = server.library;
  const canShare = workspacesEnabled && workspace !== null && library?.editable === true;
  const [target, setTarget] = useState<SaveTarget>("browser");
  const [name, setName] = useState("");
  const [folder, setFolder] = useState("");
  const [asNew, setAsNew] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [left, setLeft] = useState<string[] | null>(null);
  const suggestion = (() => {
    try {
      return new URL(draft.endpoint).pathname.split("/").filter(Boolean).pop() ?? "";
    } catch {
      return "";
    }
  })();
  const folders = [...new Set((library?.requests ?? []).map((r) => r.folder ?? "").filter(Boolean))];

  // Each time it opens: into the workspace when there is one to share with, over the request it was opened from if any.
  useEffect(() => {
    if (!open) return;
    setTarget(canShare ? "workspace" : "browser");
    setName(origin?.name ?? "");
    setFolder(origin?.folder ?? "");
    setAsNew(false);
    setProblem(null);
    setLeft(null);
    // Only when it opens: what is typed must not be replaced by the server answering again meanwhile.
  }, [open]);

  const shared = target === "workspace";
  const updating = shared && origin !== null && !asNew;
  const submit = async () => {
    if (busy) return;
    if (!shared) {
      saveCurrent(name || suggestion);
      onOpenChange(false);
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      const warnings = await saveShared({ name: name || suggestion || "Untitled request", folder, asNew });
      if (warnings.length > 0) setLeft(warnings);
      else onOpenChange(false);
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => !busy && onOpenChange(next)}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{workspacesEnabled ? "Save the request" : "Save to the library"}</DialogTitle>
          <DialogDescription>
            {shared
              ? <>Kept on the server, in the workspace “{workspace}”: everyone who opens it sees this request, with its headers, variables and the
                paths of its attachments. Passwords, tokens and the values of headers that look like credentials are not saved; the body is saved as it is.</>
              : "Saves the request, its headers and variables in this browser. Passwords, tokens and attachments are not saved."}
          </DialogDescription>
        </DialogHeader>
        {left ? (
          <Alert variant="warning">
            <AlertIcon />
            <AlertDescription>
              Saved, but not everything:
              <ul className="mt-1 list-disc space-y-1 ps-4">{left.map((w) => <li key={w}>{w}</li>)}</ul>
            </AlertDescription>
          </Alert>
        ) : (
          <div className="flex flex-col gap-4">
            {workspacesEnabled && (
              <Segmented<SaveTarget>
                label="Where to save it"
                value={target}
                onChange={setTarget}
                options={[
                  { value: "workspace", label: `Workspace${workspace ? `: ${workspace}` : ""}`, disabled: !canShare,
                    title: canShare ? "Shared with everyone who opens the workspace" : library?.readOnlyReason ?? "There is no workspace to save into" },
                  { value: "browser", label: "This browser", title: "Private to this browser" },
                ]}
              />
            )}
            {workspacesEnabled && !canShare && library?.readOnlyReason && (
              <p className="text-xs text-gray-500 dark:text-gray-400">{library.readOnlyReason}</p>
            )}
            <div>
              {shared && <label htmlFor="save-name" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">Name</label>}
              <Input id="save-name" autoFocus aria-label="Name" placeholder={suggestion || "Name of the request"} value={name}
                onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Enter" && void submit()} />
            </div>
            {shared && (
              <>
                <div>
                  <label htmlFor="save-folder" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">Folder (optional)</label>
                  <Input id="save-folder" aria-label="Folder" list="library-folders" placeholder="To group requests in the Library" value={folder}
                    onChange={(e) => setFolder(e.target.value)} onKeyDown={(e) => e.key === "Enter" && void submit()} />
                  <datalist id="library-folders">{folders.map((f) => <option key={f} value={f} />)}</datalist>
                </div>
                {origin && (
                  <label className="flex items-start gap-3">
                    <Switch checked={asNew} onCheckedChange={setAsNew} aria-label="Save as a new request" />
                    <span className="text-sm text-gray-800 dark:text-white/90">
                      Save as a new request
                      <span className="block text-xs text-gray-500 dark:text-gray-400">
                        {asNew ? `“${origin.name}” stays as it is.` : `Otherwise this replaces “${origin.name}”, which this request was opened from.`}
                      </span>
                    </span>
                  </label>
                )}
              </>
            )}
            {problem && <Alert variant="destructive"><AlertIcon /><AlertDescription>{problem}</AlertDescription></Alert>}
          </div>
        )}
        <DialogFooter>
          {left ? (
            <Button onClick={() => onOpenChange(false)}>Done</Button>
          ) : (
            <>
              <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
              <Button onClick={() => void submit()} disabled={busy}>{busy && <Spinner />} {updating ? "Update" : "Save"}</Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function EndpointBar() {
  const { draft, patch, send, cancel, sending, server, resetDraft, settings, setSettings, navigate, origin } = useApp();
  const [saving, setSaving] = useState(false);
  const profiles = server.profiles?.profiles ?? [];

  return (
    <Card size="sm" className="gap-3 sm:p-4">
      <div className="flex flex-col gap-3 lg:flex-row">
        <NativeSelect
          aria-label="SOAP version"
          className="lg:w-44"
          value={draft.soapVersion}
          onChange={(e) => patch({ soapVersion: e.target.value as "auto" | SoapVersion })}
        >
          <option value="auto">SOAP: auto-detect</option>
          <option value="1.1">SOAP 1.1</option>
          <option value="1.2">SOAP 1.2</option>
        </NativeSelect>
        <div className="relative min-w-0 flex-1">
          <GlobeIcon className="pointer-events-none absolute start-3.5 top-1/2 size-5 -translate-y-1/2 text-gray-400" />
          <Input
            aria-label="Endpoint URL"
            className="ps-11"
            placeholder={draft.profile ? "Uses the profile's endpoint (type to override)" : "https://host/service"}
            spellCheck={false}
            autoComplete="off"
            value={draft.endpoint}
            onChange={(e) => patch({ endpoint: e.target.value })}
            onKeyDown={(e) => e.key === "Enter" && !sending && void send()}
          />
        </div>
        <Input
          aria-label="SOAP action"
          className="lg:w-56"
          placeholder="SOAP action (optional)"
          title="SOAPAction header for SOAP 1.1, action parameter of the Content-Type for SOAP 1.2"
          spellCheck={false}
          autoComplete="off"
          value={draft.soapAction}
          onChange={(e) => patch({ soapAction: e.target.value })}
          onKeyDown={(e) => e.key === "Enter" && !sending && void send()}
        />
        {sending ? (
          <Button variant="outline" size="lg" onClick={cancel}><Spinner /> Cancel</Button>
        ) : (
          <Button size="lg" onClick={() => void send()} title="Send (Ctrl+Enter)"><SendIcon /> Send</Button>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-x-4 gap-y-3">
        {profiles.length > 0 && (
          <div className="flex items-center gap-2">
            <NativeSelect aria-label="Server profile" className="h-10 w-52" value={draft.profile} onChange={(e) => patch({ profile: e.target.value })}>
              <option value="">No profile</option>
              {profiles.map((p) => <option key={p.name} value={p.name}>Profile: {p.name}</option>)}
            </NativeSelect>
            {draft.profile && <Button variant="ghost" size="sm" onClick={() => navigate("profiles")}>View profile</Button>}
          </div>
        )}
        {origin && (
          <span className="min-w-0 truncate text-xs text-gray-500 dark:text-gray-400" title="Save can replace it">
            Saved request: <span className="font-medium text-gray-700 dark:text-gray-300">{origin.folder ? `${origin.folder} / ` : ""}{origin.name}</span>
          </span>
        )}
        <div className="ms-auto flex flex-wrap items-center gap-2">
          <span className="hidden text-xs text-gray-500 md:inline dark:text-gray-400"><Kbd>Ctrl</Kbd> + <Kbd>Enter</Kbd> to send</span>
          <Segmented<RequestLayout>
            label="Layout of the request and the response"
            value={settings.requestLayout}
            onChange={(requestLayout) => setSettings({ requestLayout })}
            options={[
              { value: "side", label: <ColumnsIcon className="size-5" />, ariaLabel: "Side by side", title: "Request and response side by side" },
              { value: "stacked", label: <RowsIcon className="size-5" />, ariaLabel: "Stacked", title: "Response below the request" },
            ]}
          />
          <Button variant="ghost" size="sm" onClick={resetDraft} title="Start a new request"><XIcon /> New</Button>
          <Button variant="outline" size="sm" onClick={() => setSaving(true)}><FloppyDiscIcon /> Save</Button>
          <CopyAsMenu />
        </div>
      </div>
      <SaveDialog open={saving} onOpenChange={setSaving} />
    </Card>
  );
}
