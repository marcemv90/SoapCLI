import { useEffect, useState } from "react";
import { CodeEditor } from "@/components/common/CodeEditor";
import { CopyButton, EmptyState, Kbd, StatusBadge } from "@/components/common/controls";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Spinner } from "@/components/ui/spinner";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { AlertIcon, DownloadIcon, InfoCircleIcon, SendIcon } from "@/icons";
import { base64ToBytes, downloadFile, formatBytes, formatDuration } from "@/lib/format";
import { probeReachable } from "@/lib/transports/browser";
import type { HeaderPair, RequestView, ResponseView } from "@/lib/types";
import { useWorkspace, type UiError } from "@/state/workspace";

export type LiveRequest = RequestView | { error: string } | null;

function HeadersTable({ headers, empty }: { headers: HeaderPair[]; empty: string }) {
  if (headers.length === 0) return <p className="text-sm text-gray-500 dark:text-gray-400">{empty}</p>;
  return (
    <div className="overflow-x-auto rounded-xl border border-gray-200 dark:border-gray-800">
      <table className="min-w-full text-sm">
        <tbody className="divide-y divide-gray-200 dark:divide-gray-800">
          {headers.map(([name, value], i) => (
            <tr key={`${name}-${i}`}>
              <th scope="row" className="w-1/3 bg-gray-50 px-4 py-2.5 text-start align-top font-medium break-all text-gray-700 dark:bg-white/3 dark:text-gray-300">{name}</th>
              <td className="px-4 py-2.5 font-mono text-xs break-all text-gray-800 dark:text-white/90">{value}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ErrorPanel({ error }: { error: UiError }) {
  const { draft, send, server } = useWorkspace();
  const [probe, setProbe] = useState<"idle" | "checking" | "reachable" | "unreachable">("idle");
  const titles: Record<UiError["kind"], string> = {
    config: "Check the request",
    timeout: "The server took too long",
    network: "The browser could not reach the service",
    cancelled: "Cancelled",
    server: "The SoapCLI server reported a problem",
  };
  const viaServerAvailable = server.info !== null && draft.transport === "browser" && (error.kind === "network" || error.kind === "timeout");

  const diagnose = async () => {
    setProbe("checking");
    setProbe((await probeReachable(draft.endpoint.trim())) ? "reachable" : "unreachable");
  };

  return (
    <div className="flex flex-col gap-4">
      <Alert variant={error.kind === "cancelled" ? "info" : "destructive"}>
        <AlertIcon />
        <AlertTitle>{titles[error.kind]}</AlertTitle>
        <AlertDescription>{error.message}</AlertDescription>
      </Alert>
      {error.hints.length > 0 && (
        <div>
          <h4 className="mb-2 text-sm font-semibold text-gray-800 dark:text-white/90">What usually causes this</h4>
          <ul className="list-disc space-y-2 ps-5 text-sm text-gray-600 dark:text-gray-400">
            {error.hints.map((h) => <li key={h}>{h}</li>)}
          </ul>
        </div>
      )}
      {error.kind === "network" && draft.transport === "browser" && (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap gap-2">
            <Button variant="outline" size="sm" onClick={() => void diagnose()} disabled={probe === "checking"}>
              {probe === "checking" && <Spinner />} Check if the host answers
            </Button>
            {viaServerAvailable && (
              <Button size="sm" onClick={() => void send({ transport: "server" })}><SendIcon /> Send through the SoapCLI server</Button>
            )}
          </div>
          {probe === "reachable" && (
            <Alert variant="warning">
              <InfoCircleIcon />
              <AlertTitle>Something answers at that address</AlertTitle>
              <AlertDescription>So the browser is most likely blocking the answer because the service does not allow cross-origin requests (CORS). Enable CORS on the service, or send through the SoapCLI server.</AlertDescription>
            </Alert>
          )}
          {probe === "unreachable" && (
            <Alert variant="destructive">
              <AlertIcon />
              <AlertTitle>Nothing answers at that address</AlertTitle>
              <AlertDescription>Check the host name, the port, the VPN or firewall, and whether the page is HTTPS while the service is plain HTTP.</AlertDescription>
            </Alert>
          )}
        </div>
      )}
      {viaServerAvailable && error.kind === "timeout" && (
        <div><Button size="sm" variant="outline" onClick={() => void send({ transport: "server" })}><SendIcon /> Try through the SoapCLI server</Button></div>
      )}
    </div>
  );
}

function ResponseBody({ response }: { response: ResponseView }) {
  const redirect = response.redirectLocation ?? (response.status >= 300 && response.status < 400 ? "another URL" : null);
  return (
    <div className="flex flex-col gap-3">
      {response.notes.map((n) => (
        <Alert key={n} variant="info"><InfoCircleIcon /><AlertDescription>{n}</AlertDescription></Alert>
      ))}
      {redirect && !response.opaqueRedirect && (
        <Alert variant="warning">
          <InfoCircleIcon />
          <AlertTitle>The server redirected to {redirect}</AlertTitle>
          <AlertDescription>The redirect was not followed. Fix the endpoint, or turn on “Follow redirects” in Options.</AlertDescription>
        </Alert>
      )}
      {response.binary ? (
        <EmptyState icon={<DownloadIcon />} title="Binary response" action={response.bodyBase64 !== undefined ? (
          <Button size="sm" variant="outline" onClick={() => downloadFile("response.bin", base64ToBytes(response.bodyBase64!), response.contentType ?? "application/octet-stream")}>
            <DownloadIcon /> Download ({formatBytes(response.size)})
          </Button>
        ) : undefined}>
          {response.contentType?.startsWith("multipart/") ? "This is a multipart (MTOM) message, which is not shown as text." : "This content is not text."}
        </EmptyState>
      ) : response.body === "" ? (
        <p className="py-6 text-center text-sm text-gray-500 dark:text-gray-400">The response has no body.</p>
      ) : (
        <CodeEditor readOnly value={response.body} minHeight="260px" />
      )}
    </div>
  );
}

export function RequestInspector({ view, onRefresh }: { view: LiveRequest; onRefresh?: () => void }) {
  if (view === null) {
    return (
      <div className="flex flex-col items-start gap-3">
        <p className="text-sm text-gray-500 dark:text-gray-400">The server builds the request, so it has to be asked to show it.</p>
        {onRefresh && <Button variant="outline" size="sm" onClick={onRefresh}>Show the request</Button>}
      </div>
    );
  }
  if ("error" in view) {
    return <Alert variant="warning"><AlertIcon /><AlertTitle>The request cannot be built yet</AlertTitle><AlertDescription>{view.error}</AlertDescription></Alert>;
  }
  return (
    <div className="flex flex-col gap-4">
      {view.warnings.map((w) => <Alert key={w} variant="warning"><AlertIcon /><AlertDescription>{w}</AlertDescription></Alert>)}
      <div className="flex flex-wrap items-center gap-2 text-sm text-gray-600 dark:text-gray-400">
        <Badge variant="light" color="primary" size="sm">POST</Badge>
        <span className="font-mono text-xs break-all text-gray-800 dark:text-white/90">{view.url}</span>
        <Badge variant="light" color="gray" size="sm">SOAP {view.soapVersion}</Badge>
        <Badge variant="light" color="gray" size="sm">{formatBytes(view.contentLength)}</Badge>
        {view.mtom && <Badge variant="light" color="info" size="sm">MTOM</Badge>}
        {onRefresh && <Button variant="ghost" size="xs" onClick={onRefresh}>Refresh</Button>}
      </div>
      <HeadersTable headers={view.headers} empty="No headers." />
      {view.attachments.length > 0 && (
        <p className="text-sm text-gray-600 dark:text-gray-400">Attachments: {view.attachments.join(", ")}</p>
      )}
      <CodeEditor readOnly value={view.previewXml} minHeight="160px" maxHeight="50vh" ariaLabel="Request body preview" />
    </div>
  );
}

export function ResponsePanel({ live }: { live: LiveRequest }) {
  const { response, error, sending, draft, serverPreview, refreshServerPreview } = useWorkspace();
  const [tab, setTab] = useState("body");
  // An error is only shown in the Body tab: make sure the user lands there instead of staring at another tab.
  useEffect(() => {
    if (error) setTab("body");
  }, [error]);
  const requestView: LiveRequest = draft.transport === "browser" ? live : (serverPreview ?? response?.request ?? null);

  return (
    <Card className="min-h-[520px] gap-0 overflow-hidden p-0 sm:p-0">
      {response && !error && !sending ? (
        <div className="flex flex-wrap items-center gap-3 border-b border-gray-200 px-4 py-3 sm:px-5 dark:border-gray-800">
          <StatusBadge status={response.status} text={response.statusText} />
          <span className="text-sm text-gray-500 dark:text-gray-400">
            {formatDuration(response.elapsedMs)} · {formatBytes(response.size)}
          </span>
          <Badge variant="light" color="gray" size="sm">{response.via === "browser" ? "from this browser" : "via SoapCLI server"}</Badge>
          <div className="ms-auto flex gap-2">
            {!response.binary && response.body !== "" && <CopyButton text={response.rawBody ?? response.body} />}
            {!response.binary && response.body !== "" && (
              <Button variant="outline" size="xs" onClick={() => downloadFile("response.xml", response.rawBody ?? response.body, "application/xml")}>
                <DownloadIcon /> Download
              </Button>
            )}
          </div>
        </div>
      ) : (
        <div className="border-b border-gray-200 px-4 py-4 sm:px-5 dark:border-gray-800">
          <h2 className="text-base font-semibold text-gray-800 dark:text-white/90">Response</h2>
        </div>
      )}
      <Tabs value={tab} onValueChange={(v) => setTab(String(v))} className="gap-0">
        <TabsList variant="line" className="overflow-x-auto px-3 sm:px-4">
          <TabsTrigger value="body">Body</TabsTrigger>
          <TabsTrigger value="headers">Headers{response ? ` (${response.headers.length})` : ""}</TabsTrigger>
          <TabsTrigger value="raw">Raw</TabsTrigger>
          <TabsTrigger value="request">Request</TabsTrigger>
        </TabsList>
        <TabsContent value="body" className="p-4 sm:p-5">
          {error ? <ErrorPanel error={error} />
            : sending ? <div className="flex items-center justify-center gap-3 py-16 text-sm text-gray-500 dark:text-gray-400"><Spinner className="size-5" /> Waiting for the server…</div>
            : response ? <ResponseBody response={response} />
            : <EmptyState icon={<SendIcon />} title="No response yet">
                Write or paste a SOAP request, enter the endpoint and press <Kbd>Send</Kbd>. The answer appears here, formatted and highlighted.
              </EmptyState>}
        </TabsContent>
        <TabsContent value="headers" className="p-4 sm:p-5">
          {response ? (
            <div className="flex flex-col gap-3">
              <HeadersTable headers={response.headers} empty="The server sent no readable headers." />
              {response.via === "browser" && (
                <p className="text-xs text-gray-500 dark:text-gray-400">
                  A browser only shows the response headers the server exposes with Access-Control-Expose-Headers, plus a few safe ones. Use the SoapCLI server transport to see them all.
                </p>
              )}
            </div>
          ) : <p className="text-sm text-gray-500 dark:text-gray-400">No response yet.</p>}
        </TabsContent>
        <TabsContent value="raw" className="p-4 sm:p-5">
          {response && !response.binary
            ? <pre className="max-h-[60vh] overflow-auto rounded-xl border border-gray-200 bg-gray-50 p-4 font-mono text-xs whitespace-pre-wrap text-gray-800 dark:border-gray-800 dark:bg-white/3 dark:text-white/90">{response.rawBody ?? response.body}</pre>
            : <p className="text-sm text-gray-500 dark:text-gray-400">{response ? "Binary content is not shown." : "No response yet."}</p>}
        </TabsContent>
        <TabsContent value="request" className="p-4 sm:p-5">
          <RequestInspector view={requestView} onRefresh={draft.transport === "server" ? () => void refreshServerPreview() : undefined} />
        </TabsContent>
      </Tabs>
    </Card>
  );
}
