import { useState, type ReactNode } from "react";
import { Segmented } from "@/components/common/controls";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { formatBytes } from "@/lib/format";
import type { RequestLayout } from "@/lib/storage";
import { useTheme, type ThemeMode } from "@/state/theme";
import { useApp } from "@/state/app";

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg font-semibold text-gray-800 dark:text-white/90">{title}</CardTitle>
        {description && <CardDescription className="text-sm text-gray-500 dark:text-gray-400">{description}</CardDescription>}
      </CardHeader>
      <CardContent className="flex flex-col gap-5">{children}</CardContent>
    </Card>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-b border-gray-100 pb-3 last:border-0 last:pb-0 dark:border-gray-800">
      <span className="text-sm text-gray-500 dark:text-gray-400">{label}</span>
      <span className="text-sm font-medium text-gray-800 dark:text-white/90">{children}</span>
    </div>
  );
}

function ConfirmButton({ label, onConfirm, disabled }: { label: string; onConfirm: () => void; disabled?: boolean }) {
  const [armed, setArmed] = useState(false);
  return (
    <Button variant={armed ? "destructive" : "outline"} size="sm" disabled={disabled}
      onClick={() => { if (armed) { onConfirm(); setArmed(false); } else { setArmed(true); setTimeout(() => setArmed(false), 3000); } }}>
      {armed ? "Click again to confirm" : label}
    </Button>
  );
}

export function SettingsPage() {
  const { mode, setMode } = useTheme();
  const { settings, setSettings, server, saved, history, clearHistory, clearAllData, navigate, workspace, workspacesEnabled } = useApp();
  const info = server.info;
  return (
    <div className="grid grid-cols-1 gap-4 md:gap-6 xl:grid-cols-2">
      <Section title="Appearance">
        <Segmented<ThemeMode> label="Theme" value={mode} onChange={setMode}
          options={[{ value: "light", label: "Light" }, { value: "dark", label: "Dark" }, { value: "auto", label: "System" }]} />
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium text-gray-700 dark:text-gray-300">Request and response</span>
          <Segmented<RequestLayout> label="Layout of the request page" value={settings.requestLayout} onChange={(requestLayout) => setSettings({ requestLayout })}
            options={[{ value: "side", label: "Side by side" }, { value: "stacked", label: "Stacked" }]} />
        </div>
      </Section>

      <Section title="Defaults for new requests" description="Used when you start a new request. The request you are editing keeps its own values.">
        <div className="max-w-xs">
          <label htmlFor="default-timeout" className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">Timeout (seconds)</label>
          <Input id="default-timeout" type="number" min={0} value={settings.timeoutSeconds}
            onChange={(e) => setSettings({ timeoutSeconds: Math.max(0, Number(e.target.value) || 0) })} />
        </div>
        <label className="flex items-center gap-3">
          <Switch checked={settings.followRedirects} onCheckedChange={(followRedirects) => setSettings({ followRedirects })} aria-label="Follow redirects by default" />
          <span className="text-sm text-gray-800 dark:text-white/90">Follow redirects</span>
        </label>
      </Section>

      <Section title="SoapCLI server" description="The server this page came from. It makes every call, with its own certificates, proxies and profiles.">
        {info ? (
          <div className="flex flex-col gap-3">
            <Row label="Status"><Badge variant="light" color="success" size="sm">connected</Badge></Row>
            <Row label="Version">{info.version}</Row>
            <Row label="Destinations">{info.allowedHosts.length === 0 ? "any host" : info.allowedHosts.join(", ")}</Row>
            {workspacesEnabled && (
              <>
                <Row label="Workspaces folder"><span className="break-all font-mono text-xs">{info.workspacesDir}</span>{" "}
                  <button type="button" onClick={() => navigate("workspaces")} className="font-medium text-brand-500 hover:underline dark:text-brand-400">View</button>
                </Row>
                <Row label="Open workspace">{workspace ?? "none"}</Row>
                {info.workspace && info.workspace.allowedHosts.length > 0 && (
                  <Row label="This workspace can only call"><span className="break-all font-mono text-xs">{info.workspace.allowedHosts.join(", ")}</span></Row>
                )}
                <Row label="Saved requests of the workspace">
                  {server.library ? (server.library.editable ? "can be saved here" : "read-only") : "unknown"}
                </Row>
              </>
            )}
            <Row label="Skip TLS checks">{info.insecureTlsAllowed ? "allowed" : "disabled"}</Row>
            <Row label="Largest request">{formatBytes(info.maxBodyBytes)}</Row>
            <Row label="Placeholders file">
              <span className="break-all font-mono text-xs">{info.placeholdersFile}</span>
              {" "}
              <button type="button" onClick={() => navigate("placeholders")} className="font-medium text-brand-500 hover:underline dark:text-brand-400">View</button>
            </Row>
            <Row label="Editing profiles and placeholders">
              {!info.configEditAllowed ? "off on this server" : server.profiles?.editable === false ? "allowed, but the files are read-only" : "allowed"}
            </Row>
            <Row label="Attachments folder"><span className="break-all font-mono text-xs">{info.attachmentsDir}</span></Row>
            <Row label={workspacesEnabled ? "Profiles file of the workspace" : "Profiles file"}>{info.profilesFileFound ? info.profilesFile : "none found"}</Row>
            <Row label="Profiles">
              {server.profiles?.error ? "invalid file" : server.profiles?.profiles.length ?? 0}
              {" "}
              <button type="button" onClick={() => navigate("profiles")} className="font-medium text-brand-500 hover:underline dark:text-brand-400">View</button>
            </Row>
          </div>
        ) : (
          <p className="text-sm text-gray-600 dark:text-gray-400">
            This page was not served by a SoapCLI server, so it cannot send anything. Start one with{" "}
            <code className="rounded bg-gray-100 px-1.5 py-0.5 dark:bg-white/10">java -jar SoapCLI.jar serve</code> or the Docker image and open the address it prints.
          </p>
        )}
      </Section>

      <Section title="Your data" description={workspacesEnabled
        ? "The requests saved in a workspace are on the server. What is listed here is kept in this browser only."
        : "Everything is stored in this browser only."}>
        <Row label={workspacesEnabled ? "Requests saved in this browser" : "Saved requests"}>{saved.length}</Row>
        <Row label="History entries">{history.length}</Row>
        <div className="flex flex-wrap gap-2">
          <ConfirmButton label="Clear history" onConfirm={clearHistory} disabled={history.length === 0} />
          <ConfirmButton label="Delete everything" onConfirm={clearAllData} disabled={history.length === 0 && saved.length === 0} />
        </div>
      </Section>

      <Section title="About">
        <Row label="Version">{info?.version ?? "unknown"}</Row>
        <Row label="Documentation"><a className="text-brand-500 hover:underline dark:text-brand-400" href="https://marcemv90.github.io/SoapCLI/" target="_blank" rel="noopener noreferrer">marcemv90.github.io/SoapCLI</a></Row>
        <Row label="Source code"><a className="text-brand-500 hover:underline dark:text-brand-400" href="https://github.com/marcemv90/SoapCLI" target="_blank" rel="noopener noreferrer">github.com/marcemv90/SoapCLI</a></Row>
        <p className="text-xs text-gray-500 dark:text-gray-400">The interface is built with the free TailAdmin template (MIT).</p>
      </Section>
    </div>
  );
}
