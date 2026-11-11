import { useState, type ReactNode } from "react";
import { Segmented } from "@/components/common/controls";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { formatBytes } from "@/lib/format";
import type { Transport } from "@/lib/types";
import { useTheme, type ThemeMode } from "@/state/theme";
import { useWorkspace } from "@/state/workspace";

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
  const { settings, setSettings, server, saved, history, clearHistory, clearAllData } = useWorkspace();
  const info = server.info;
  return (
    <div className="grid grid-cols-1 gap-4 md:gap-6 xl:grid-cols-2">
      <Section title="Appearance">
        <Segmented<ThemeMode> label="Theme" value={mode} onChange={setMode}
          options={[{ value: "light", label: "Light" }, { value: "dark", label: "Dark" }, { value: "auto", label: "System" }]} />
      </Section>

      <Section title="Defaults for new requests" description="Used when you start a new request. The request you are editing keeps its own values.">
        <div className="flex flex-col gap-2">
          <span className="text-sm font-medium text-gray-700 dark:text-gray-300">Make calls from</span>
          <Segmented<Transport> label="Default transport" value={settings.defaultTransport} onChange={(defaultTransport) => setSettings({ defaultTransport })}
            options={[{ value: "browser", label: "This browser" }, { value: "server", label: "SoapCLI server", disabled: !info }]} />
        </div>
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

      <Section title="SoapCLI server" description="The optional back end this page may have been served by. It adds what a browser cannot do.">
        {info ? (
          <div className="flex flex-col gap-3">
            <Row label="Status"><Badge variant="light" color="success" size="sm">connected</Badge></Row>
            <Row label="Version">{info.version}</Row>
            <Row label="Destinations">{info.allowedHosts.length === 0 ? "any host" : info.allowedHosts.join(", ")}</Row>
            <Row label="Skip TLS checks">{info.insecureTlsAllowed ? "allowed" : "disabled"}</Row>
            <Row label="Largest request">{formatBytes(info.maxBodyBytes)}</Row>
            <Row label="Profiles file">{info.profilesFileFound ? info.profilesFile : "none found"}</Row>
            {server.profiles?.error && <p className="text-sm text-red-600 dark:text-red-400">{server.profiles.error}</p>}
            {(server.profiles?.profiles.length ?? 0) > 0 && (
              <div className="flex flex-wrap gap-2 pt-1">
                {server.profiles!.profiles.map((p) => <Badge key={p.name} variant="light" color={p.error ? "error" : "primary"} size="sm" title={p.error}>{p.name}</Badge>)}
              </div>
            )}
          </div>
        ) : (
          <p className="text-sm text-gray-600 dark:text-gray-400">
            This page was not served by a SoapCLI server, so every call is made straight from your browser. Start one with{" "}
            <code className="rounded bg-gray-100 px-1.5 py-0.5 dark:bg-white/10">java -jar SoapCLI.jar serve</code> or the Docker image to also send through it:
            it can use client certificates, trust stores and proxies, skip TLS checks, and reach services that do not allow CORS.
          </p>
        )}
      </Section>

      <Section title="Your data" description="Everything is stored in this browser only.">
        <Row label="Saved requests">{saved.length}</Row>
        <Row label="History entries">{history.length}</Row>
        <div className="flex flex-wrap gap-2">
          <ConfirmButton label="Clear history" onConfirm={clearHistory} disabled={history.length === 0} />
          <ConfirmButton label="Delete everything" onConfirm={clearAllData} disabled={history.length === 0 && saved.length === 0} />
        </div>
      </Section>

      <Section title="About">
        <Row label="Version">{info?.version ?? "standalone"}</Row>
        <Row label="Documentation"><a className="text-brand-500 hover:underline dark:text-brand-400" href="https://marcemv90.github.io/SoapCLI/" target="_blank" rel="noopener noreferrer">marcemv90.github.io/SoapCLI</a></Row>
        <Row label="Source code"><a className="text-brand-500 hover:underline dark:text-brand-400" href="https://github.com/marcemv90/SoapCLI" target="_blank" rel="noopener noreferrer">github.com/marcemv90/SoapCLI</a></Row>
        <p className="text-xs text-gray-500 dark:text-gray-400">The interface is built with the free TailAdmin template (MIT).</p>
      </Section>
    </div>
  );
}
