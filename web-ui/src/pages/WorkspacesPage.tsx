import { useState } from "react";
import { EmptyState } from "@/components/common/controls";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Spinner } from "@/components/ui/spinner";
import { AlertIcon, LayoutGridIcon, LockIcon, PlusIcon } from "@/icons";
import { WORKSPACE_NAME } from "@/lib/route";
import type { WorkspaceSummary } from "@/lib/transports/server";
import { useApp } from "@/state/app";

const plural = (n: number, one: string, many = `${one}s`) => `${n} ${n === 1 ? one : many}`;

function NewWorkspaceDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { createWorkspace, navigate } = useApp();
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const valid = WORKSPACE_NAME.test(name);

  const submit = async () => {
    if (!valid || busy) return;
    setBusy(true);
    setProblem(null);
    try {
      await createWorkspace(name, description);
      setName("");
      setDescription("");
      onOpenChange(false);
      navigate("request");
    } catch (e) {
      setProblem(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(next) => { if (!busy) { setProblem(null); onOpenChange(next); } }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>New workspace</DialogTitle>
          <DialogDescription>
            A folder on the server with its own profiles, placeholder options, attachments and saved requests, shared by everyone who opens it.
          </DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-4">
          <div>
            <label htmlFor="workspace-name" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">Name</label>
            <Input id="workspace-name" autoFocus placeholder="billing" spellCheck={false} autoComplete="off" value={name}
              aria-invalid={name !== "" && !valid ? true : undefined}
              onChange={(e) => setName(e.target.value.trim())} onKeyDown={(e) => e.key === "Enter" && void submit()} />
            <p className="mt-1.5 text-xs text-gray-500 dark:text-gray-400">
              It is the name of the folder: letters, digits, <code>.</code> <code>_</code> <code>-</code>, starting with a letter or a digit.
            </p>
          </div>
          <div>
            <label htmlFor="workspace-description" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">Description (optional)</label>
            <Input id="workspace-description" placeholder="What it is for, and who" maxLength={300} value={description}
              onChange={(e) => setDescription(e.target.value)} onKeyDown={(e) => e.key === "Enter" && void submit()} />
          </div>
          {problem && <Alert variant="destructive"><AlertIcon /><AlertDescription>{problem}</AlertDescription></Alert>}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>Cancel</Button>
          <Button onClick={() => void submit()} disabled={!valid || busy}>{busy && <Spinner />} Create</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function ConfirmButton({ label, confirm, onConfirm, busy }: { label: string; confirm: string; onConfirm: () => void; busy?: boolean }) {
  const [armed, setArmed] = useState(false);
  return (
    <Button variant={armed ? "destructive" : "outline"} size="sm" disabled={busy}
      onClick={() => { if (armed) { setArmed(false); onConfirm(); } else { setArmed(true); setTimeout(() => setArmed(false), 3000); } }}>
      {busy && <Spinner />} {armed ? confirm : label}
    </Button>
  );
}

function WorkspaceCard({ workspace, current, manageable, busy, onOpen, onRemove }: {
  workspace: WorkspaceSummary;
  current: boolean;
  manageable: boolean;
  busy: boolean;
  onOpen: () => void;
  onRemove: () => void;
}) {
  const counts = [
    workspace.profiles !== undefined ? plural(workspace.profiles, "profile") : null,
    workspace.placeholders !== undefined ? plural(workspace.placeholders, "placeholder") : null,
    workspace.requests !== undefined ? plural(workspace.requests, "saved request") : null,
  ].filter(Boolean);
  return (
    <Card className="gap-3" aria-label={`Workspace ${workspace.name}`}>
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-base font-semibold break-all text-gray-800 dark:text-white/90">{workspace.name}</h2>
        {current && <Badge variant="light" color="primary" size="sm">open</Badge>}
        {workspace.readOnly && <Badge variant="light" color="warning" size="sm"><LockIcon className="size-3" /> read-only</Badge>}
      </div>
      <p className="min-h-5 text-sm text-gray-600 dark:text-gray-400">{workspace.description || <span className="text-gray-400">No description</span>}</p>
      {counts.length > 0 && <p className="text-xs text-gray-500 dark:text-gray-400">{counts.join(" · ")}</p>}
      {workspace.allowedHosts.length > 0 && (
        <p className="text-xs text-gray-500 dark:text-gray-400">Can only call: <code className="break-all">{workspace.allowedHosts.join(", ")}</code></p>
      )}
      {workspace.error && <Alert variant="destructive"><AlertIcon /><AlertDescription>{workspace.error}</AlertDescription></Alert>}
      <div className="mt-auto flex flex-wrap gap-2 pt-1">
        {!current && <Button size="sm" onClick={onOpen}>Open</Button>}
        {manageable && !workspace.readOnly && <ConfirmButton label="Remove" confirm="Click again to remove" onConfirm={onRemove} busy={busy} />}
      </div>
    </Card>
  );
}

export function WorkspacesPage() {
  const { server, workspace, switchWorkspace, removeWorkspace, navigate } = useApp();
  const [creating, setCreating] = useState(false);
  const [removing, setRemoving] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const info = server.workspaces;

  if (server.loaded && !server.info) {
    return <Card><EmptyState icon={<LayoutGridIcon />} title="No SoapCLI server">Workspaces live on the SoapCLI server, and this page was not served by one.</EmptyState></Card>;
  }
  if (!info) return null;
  if (!info.enabled) {
    return (
      <Card>
        <EmptyState icon={<LayoutGridIcon />} title="This server does not use workspaces">
          Start it with <code className="rounded bg-gray-100 px-1.5 py-0.5 dark:bg-white/10">--workspaces-dir</code> (or{" "}
          <code className="rounded bg-gray-100 px-1.5 py-0.5 dark:bg-white/10">SOAPCLI_WORKSPACES_DIR</code>) to keep profiles, saved requests and
          attachments in folders that a team shares.
        </EmptyState>
      </Card>
    );
  }

  const open = async (name: string) => {
    await switchWorkspace(name);
    navigate("request");
  };
  const remove = async (name: string) => {
    setRemoving(name);
    setNotice(null);
    try {
      const movedTo = await removeWorkspace(name);
      setNotice({ ok: true, text: `Removed “${name}”. Nothing was deleted: its folder is now called ${movedTo}, and can be put back by renaming it.` });
    } catch (e) {
      setNotice({ ok: false, text: e instanceof Error ? e.message : String(e) });
    } finally {
      setRemoving(null);
    }
  };

  return (
    <div className="flex flex-col gap-4 md:gap-6">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm text-gray-500 dark:text-gray-400">
        <span>Workspaces folder:</span>
        <code className="rounded bg-gray-100 px-1.5 py-0.5 break-all text-gray-700 dark:bg-white/10 dark:text-gray-300">{info.dir}</code>
        {info.manageable && <Button size="xs" onClick={() => { setNotice(null); setCreating(true); }}><PlusIcon /> New workspace</Button>}
        <span className="text-xs">
          {info.manageable ? "Each folder in it is a workspace, and a folder you add by hand shows up here too."
            : info.manageReason ?? "Each folder in it is a workspace."}
        </span>
      </div>

      {notice && (
        <Alert variant={notice.ok ? "success" : "destructive"}>
          {!notice.ok && <AlertIcon />}
          <AlertDescription>{notice.text}</AlertDescription>
        </Alert>
      )}

      {info.workspaces.length === 0 ? (
        <Card>
          <EmptyState icon={<LayoutGridIcon />} title="No workspaces yet"
            action={info.manageable ? <Button size="sm" onClick={() => setCreating(true)}><PlusIcon /> Create the first workspace</Button> : undefined}>
            {info.manageable
              ? "A workspace is a folder with its own profiles, placeholder options, attachments and saved requests, shared by everyone who opens it."
              : <>Make a folder inside <code>{info.dir}</code>: each folder in it is a workspace.</>}
          </EmptyState>
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2 md:gap-6 2xl:grid-cols-3">
          {info.workspaces.map((w) => (
            <WorkspaceCard key={w.name} workspace={w} current={w.name === workspace} manageable={info.manageable}
              busy={removing === w.name} onOpen={() => void open(w.name)} onRemove={() => void remove(w.name)} />
          ))}
        </div>
      )}
      <NewWorkspaceDialog open={creating} onOpenChange={setCreating} />
    </div>
  );
}
