import { useEffect, useState } from "react";
import { AttachmentLoadDialog } from "@/components/request/AttachmentLoadDialog";
import { BodyTab } from "@/components/request/body";
import { EndpointBar } from "@/components/request/EndpointBar";
import { ResponsePanel } from "@/components/request/ResponsePanel";
import { AuthTab, HeadersTab, OptionsTab } from "@/components/request/tabs";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AlertIcon } from "@/icons";
import { useApp } from "@/state/app";

function Count({ n }: { n: number }) {
  return n > 0 ? <Badge variant="light" color="primary" size="sm" className="ms-1.5 px-1.5">{n}</Badge> : null;
}

/** Shown only when the page was opened without the SoapCLI server behind it (a static copy, or `npm run dev` alone). */
function NoServerNotice() {
  const { server, refreshServer } = useApp();
  if (!server.loaded || server.info) return null;
  return (
    <Alert variant="warning">
      <AlertIcon />
      <AlertTitle>No SoapCLI server behind this page</AlertTitle>
      <AlertDescription>
        Requests are sent by the SoapCLI server, and this page was not served by one. Start it with{" "}
        <code className="rounded bg-gray-100 px-1.5 py-0.5 dark:bg-white/10">java -jar SoapCLI.jar serve</code> (or the Docker image) and open
        the address it prints.
        <span className="mt-2 block"><Button variant="outline" size="xs" onClick={refreshServer}>Check again</Button></span>
      </AlertDescription>
    </Alert>
  );
}

export function RequestPage() {
  const { draft, send, sending, serverPreview, page, settings, notice, dismissNotice } = useApp();
  const [tab, setTab] = useState("body");
  const stacked = settings.requestLayout === "stacked";

  const warnings = serverPreview?.warnings ?? [];

  useEffect(() => {
    if (page !== "request") return;
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === "Enter" && !sending) {
        e.preventDefault();
        void send();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [page, send, sending]);

  return (
    <div className="flex flex-col gap-4 md:gap-6">
      <EndpointBar />
      <AttachmentLoadDialog />
      <NoServerNotice />
      {notice && (
        <Alert variant={notice.kind === "error" ? "destructive" : "info"}>
          <AlertIcon />
          <AlertDescription>
            {notice.text}
            <span className="mt-2 block"><Button variant="outline" size="xs" onClick={dismissNotice}>Dismiss</Button></span>
          </AlertDescription>
        </Alert>
      )}
      {warnings.length > 0 && (
        <Alert variant="warning">
          <AlertIcon />
          <AlertDescription>
            <ul className="list-disc space-y-1 ps-4">{warnings.slice(0, 4).map((w) => <li key={w}>{w}</li>)}</ul>
          </AlertDescription>
        </Alert>
      )}
      {/* Side by side from the xl breakpoint up (below it there is no room); stacked always has the response underneath. */}
      <div className={`grid grid-cols-1 items-start gap-4 md:gap-6 ${stacked ? "" : "xl:grid-cols-2"}`}>
        <Card className="min-w-0 gap-0 overflow-hidden p-0 sm:p-0">
          <Tabs value={tab} onValueChange={(v) => setTab(String(v))} className="gap-0">
            <TabsList variant="line" className="overflow-x-auto px-3 sm:px-4">
              <TabsTrigger value="body">Body</TabsTrigger>
              <TabsTrigger value="headers">Headers<Count n={draft.headers.filter((h) => h.enabled && h.name.trim()).length} /></TabsTrigger>
              <TabsTrigger value="auth">Auth{draft.auth.kind !== "none" && <Badge variant="light" color="success" size="sm" className="ms-1.5 px-1.5">on</Badge>}</TabsTrigger>
              <TabsTrigger value="options">Options</TabsTrigger>
            </TabsList>
            <TabsContent value="body" className="p-4 sm:p-5"><BodyTab /></TabsContent>
            <TabsContent value="headers" className="p-4 sm:p-5"><HeadersTab /></TabsContent>
            <TabsContent value="auth" className="p-4 sm:p-5"><AuthTab /></TabsContent>
            <TabsContent value="options" className="p-4 sm:p-5"><OptionsTab /></TabsContent>
          </Tabs>
        </Card>
        <div className="min-w-0"><ResponsePanel stacked={stacked} /></div>
      </div>
    </div>
  );
}
