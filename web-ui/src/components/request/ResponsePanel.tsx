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
import type { HeaderPair, RequestView, ResponseView } from "@/lib/types";
import { useWorkspace, type UiError } from "@/state/workspace";

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

const ERROR_TITLES: Record<string, string> = {
  config: "Check the request",
  forbidden: "The server does not allow this",
  network: "The SoapCLI server could not reach the service",
  unsupported: "Not supported",
  "too-large": "Too large",
  unavailable: "Cannot reach the SoapCLI server",
};

function ErrorPanel({ error }: { error: UiError }) {
  const { refreshServer } = useWorkspace();
  const title = error.kind === "cancelled" ? "Cancelled" : ERROR_TITLES[error.category ?? ""] ?? "The SoapCLI server reported a problem";
  return (
    <div className="flex flex-col gap-4">
      <Alert variant={error.kind === "cancelled" ? "info" : "destructive"}>
        <AlertIcon />
        <AlertTitle>{title}</AlertTitle>
        <AlertDescription>{error.message}</AlertDescription>
      </Alert>
      {error.category === "unavailable" && (
        <div><Button size="sm" variant="outline" onClick={refreshServer}>Check the server again</Button></div>
      )}
    </div>
  );
}

function ResponseBody({ response }: { response: ResponseView }) {
  const redirect = response.redirectLocation ?? (response.status >= 300 && response.status < 400 ? "another URL" : null);
  return (
    <div className="flex flex-col gap-3">
      {redirect && (
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

export function RequestInspector({ view, onRefresh }: { view: RequestView | null; onRefresh: () => void }) {
  if (view === null) {
    return (
      <div className="flex flex-col items-start gap-3">
        <p className="text-sm text-gray-500 dark:text-gray-400">The server builds the request, so it has to be asked to show it.</p>
        <Button variant="outline" size="sm" onClick={onRefresh}>Show the request</Button>
      </div>
    );
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
        <Button variant="ghost" size="xs" onClick={onRefresh}>Refresh</Button>
      </div>
      <HeadersTable headers={view.headers} empty="No headers." />
      {view.attachments.length > 0 && (
        <p className="text-sm text-gray-600 dark:text-gray-400">Attachments: {view.attachments.join(", ")}</p>
      )}
      <CodeEditor readOnly value={view.previewXml} minHeight="160px" maxHeight="50vh" ariaLabel="Request body preview" />
    </div>
  );
}

export function ResponsePanel({ stacked = false }: { stacked?: boolean }) {
  const { response, error, sending, serverPreview, refreshServerPreview } = useWorkspace();
  const [tab, setTab] = useState("body");
  // An error is only shown in the Body tab: make sure the user lands there instead of staring at another tab.
  useEffect(() => {
    if (error) setTab("body");
  }, [error]);
  const requestView = serverPreview ?? response?.request ?? null;

  return (
    <Card className={`gap-0 overflow-hidden p-0 sm:p-0 ${stacked ? "min-h-[320px]" : "min-h-[520px]"}`}>
      {response && !error && !sending ? (
        <div className="flex flex-wrap items-center gap-3 border-b border-gray-200 px-4 py-3 sm:px-5 dark:border-gray-800">
          <StatusBadge status={response.status} text={response.statusText} />
          <span className="text-sm text-gray-500 dark:text-gray-400">
            {formatDuration(response.elapsedMs)} · {formatBytes(response.size)}
          </span>
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
            </div>
          ) : <p className="text-sm text-gray-500 dark:text-gray-400">No response yet.</p>}
        </TabsContent>
        <TabsContent value="raw" className="p-4 sm:p-5">
          {response && !response.binary
            ? <pre className="max-h-[60vh] overflow-auto rounded-xl border border-gray-200 bg-gray-50 p-4 font-mono text-xs whitespace-pre-wrap text-gray-800 dark:border-gray-800 dark:bg-white/3 dark:text-white/90">{response.rawBody ?? response.body}</pre>
            : <p className="text-sm text-gray-500 dark:text-gray-400">{response ? "Binary content is not shown." : "No response yet."}</p>}
        </TabsContent>
        <TabsContent value="request" className="p-4 sm:p-5">
          <RequestInspector view={requestView} onRefresh={() => void refreshServerPreview()} />
        </TabsContent>
      </Tabs>
    </Card>
  );
}
