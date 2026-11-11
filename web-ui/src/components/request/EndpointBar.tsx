import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { ChevronDownIcon, CodeIcon, DownloadIcon, FloppyDiscIcon, GlobeIcon, SendIcon, XIcon } from "@/icons";
import { toCliCommand, toCurl } from "@/lib/command";
import { copyText, downloadFile } from "@/lib/format";
import { findPlaceholders } from "@/lib/placeholders";
import { RequestError, buildRequest } from "@/lib/request";
import type { SoapVersion } from "@/lib/soap";
import type { Transport } from "@/lib/types";
import { useWorkspace } from "@/state/workspace";
import { Kbd, NativeSelect, Segmented } from "@/components/common/controls";

function CopyAsMenu() {
  const { draft } = useWorkspace();
  const [message, setMessage] = useState<string | null>(null);

  const done = (text: string) => {
    setMessage(text);
    setTimeout(() => setMessage(null), 2000);
  };
  const copy = async (text: string, what: string) => done((await copyText(text)) ? `${what} copied` : "Could not copy");

  const curl = () => {
    try {
      const body = toCurl(draft, buildRequest(draft));
      if (body === null) return done("curl cannot send MTOM; use the CLI command");
      const note = findPlaceholders(draft.body).length > 0
        ? "# request.xml must hold the final XML: replace the ___NAME:default___ markers first\n" : "";
      void copy(note + body, "curl command");
    } catch (e) {
      done(e instanceof RequestError ? e.message : "Cannot build the request");
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
          <DropdownMenuItem onClick={() => void copy(toCliCommand(draft, "jar"), "CLI command")}>
            <CodeIcon className="size-5" /> SoapCLI command (java -jar)
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => void copy(toCliCommand(draft, "docker"), "Docker command")}>
            <CodeIcon className="size-5" /> SoapCLI command (Docker)
          </DropdownMenuItem>
          <DropdownMenuItem onClick={curl}>
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

function SaveDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { draft, saveCurrent } = useWorkspace();
  const [name, setName] = useState("");
  const suggestion = (() => {
    try {
      return new URL(draft.endpoint).pathname.split("/").filter(Boolean).pop() ?? "";
    } catch {
      return "";
    }
  })();
  const submit = () => {
    saveCurrent(name || suggestion);
    setName("");
    onOpenChange(false);
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Save to the library</DialogTitle>
          <DialogDescription>
            Saves the request, its headers and variables in this browser. Passwords, tokens and attachments are not saved.
          </DialogDescription>
        </DialogHeader>
        <Input
          autoFocus
          aria-label="Name"
          placeholder={suggestion || "Name of the request"}
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && submit()}
        />
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>Cancel</Button>
          <Button onClick={submit}>Save</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function EndpointBar() {
  const { draft, patch, send, cancel, sending, server, resetDraft } = useWorkspace();
  const [saving, setSaving] = useState(false);
  const serverReady = server.info !== null;
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
        <Segmented<Transport>
          label="Where the call is made from"
          value={draft.transport}
          onChange={(transport) => patch({ transport, profile: transport === "browser" ? "" : draft.profile })}
          options={[
            { value: "browser", label: "This browser", title: "Calls go straight from your browser: reaches anything your machine reaches, but needs CORS" },
            { value: "server", label: "SoapCLI server", disabled: !serverReady,
              title: serverReady ? "Calls are made by the SoapCLI server this page came from" : "This page was not served by a SoapCLI server" },
          ]}
        />
        {draft.transport === "server" && profiles.length > 0 && (
          <NativeSelect aria-label="Server profile" className="h-10 w-52" value={draft.profile} onChange={(e) => patch({ profile: e.target.value })}>
            <option value="">No profile</option>
            {profiles.map((p) => <option key={p.name} value={p.name}>Profile: {p.name}</option>)}
          </NativeSelect>
        )}
        <div className="ms-auto flex flex-wrap items-center gap-2">
          <span className="hidden text-xs text-gray-500 md:inline dark:text-gray-400"><Kbd>Ctrl</Kbd> + <Kbd>Enter</Kbd> to send</span>
          <Button variant="ghost" size="sm" onClick={resetDraft} title="Start a new request"><XIcon /> New</Button>
          <Button variant="outline" size="sm" onClick={() => setSaving(true)}><FloppyDiscIcon /> Save</Button>
          <CopyAsMenu />
        </div>
      </div>
      <SaveDialog open={saving} onOpenChange={setSaving} />
    </Card>
  );
}
