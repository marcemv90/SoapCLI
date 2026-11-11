import { useDeferredValue, useEffect, useMemo, useState } from "react";
import { EndpointBar } from "@/components/request/EndpointBar";
import { ResponsePanel, type LiveRequest } from "@/components/request/ResponsePanel";
import { AttachmentsTab, AuthTab, BodyTab, HeadersTab, OptionsTab, VariablesTab } from "@/components/request/tabs";
import { Alert, AlertDescription } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AlertIcon } from "@/icons";
import { findPlaceholders } from "@/lib/placeholders";
import { buildRequest } from "@/lib/request";
import { requestViewOf } from "@/lib/response";
import { useWorkspace } from "@/state/workspace";

function Count({ n }: { n: number }) {
  return n > 0 ? <Badge variant="light" color="primary" size="sm" className="ms-1.5 px-1.5">{n}</Badge> : null;
}

export function RequestPage() {
  const { draft, send, sending, serverPreview, page } = useWorkspace();
  const [tab, setTab] = useState("body");

  // What would be sent right now. Deferred, so typing in the editor never waits for it.
  const deferred = useDeferredValue(draft);
  const live = useMemo<LiveRequest>(() => {
    if (deferred.transport !== "browser") return null;
    try {
      return requestViewOf(buildRequest(deferred));
    } catch (e) {
      return { error: e instanceof Error ? e.message : String(e) };
    }
  }, [deferred]);

  const warnings = draft.transport === "browser"
    ? (live && !("error" in live) ? live.warnings : [])
    : serverPreview?.warnings ?? [];
  const placeholderCount = useMemo(() => findPlaceholders(draft.body).length, [draft.body]);

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
      {warnings.length > 0 && (
        <Alert variant="warning">
          <AlertIcon />
          <AlertDescription>
            <ul className="list-disc space-y-1 ps-4">{warnings.slice(0, 4).map((w) => <li key={w}>{w}</li>)}</ul>
          </AlertDescription>
        </Alert>
      )}
      <div className="grid grid-cols-1 items-start gap-4 md:gap-6 xl:grid-cols-2">
        <Card className="min-w-0 gap-0 overflow-hidden p-0 sm:p-0">
          <Tabs value={tab} onValueChange={(v) => setTab(String(v))} className="gap-0">
            <TabsList variant="line" className="overflow-x-auto px-3 sm:px-4">
              <TabsTrigger value="body">Body</TabsTrigger>
              <TabsTrigger value="headers">Headers<Count n={draft.headers.filter((h) => h.enabled && h.name.trim()).length} /></TabsTrigger>
              <TabsTrigger value="auth">Auth{draft.auth.kind !== "none" && <Badge variant="light" color="success" size="sm" className="ms-1.5 px-1.5">on</Badge>}</TabsTrigger>
              <TabsTrigger value="variables">Variables<Count n={placeholderCount} /></TabsTrigger>
              <TabsTrigger value="attachments">Attachments<Count n={draft.attachments.length} /></TabsTrigger>
              <TabsTrigger value="options">Options</TabsTrigger>
            </TabsList>
            <TabsContent value="body" className="p-4 sm:p-5"><BodyTab /></TabsContent>
            <TabsContent value="headers" className="p-4 sm:p-5"><HeadersTab /></TabsContent>
            <TabsContent value="auth" className="p-4 sm:p-5"><AuthTab /></TabsContent>
            <TabsContent value="variables" className="p-4 sm:p-5"><VariablesTab /></TabsContent>
            <TabsContent value="attachments" className="p-4 sm:p-5"><AttachmentsTab /></TabsContent>
            <TabsContent value="options" className="p-4 sm:p-5"><OptionsTab /></TabsContent>
          </Tabs>
        </Card>
        <div className="min-w-0"><ResponsePanel live={live} /></div>
      </div>
    </div>
  );
}
