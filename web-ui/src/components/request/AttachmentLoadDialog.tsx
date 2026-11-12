import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Progress } from "@/components/ui/progress";
import { Spinner } from "@/components/ui/spinner";
import { AlertIcon, CheckIcon } from "@/icons";
import { formatBytes } from "@/lib/format";
import { summarize, type LoadItem } from "@/lib/load-attachments";
import { useWorkspace } from "@/state/workspace";

function StateIcon({ state }: { state: LoadItem["state"] }) {
  if (state === "loading") return <Spinner className="size-4 text-brand-500" />;
  if (state === "done") return <CheckIcon className="size-4 text-green-500" aria-label="Loaded" />;
  if (state === "error") return <AlertIcon className="size-4 text-red-500" aria-label="Failed" />;
  return <span aria-label="Waiting" className="size-2 rounded-full bg-gray-300 dark:bg-gray-600" />;
}

/**
 * Shown while the files a request names by path are loaded, before the request is sent: each file with its state, and
 * how far along they all are. If one cannot be loaded it stays open with the reason, and nothing has been sent.
 */
export function AttachmentLoadDialog() {
  const { attachmentLoad, cancel, dismissAttachmentLoad, send } = useWorkspace();
  const running = attachmentLoad?.status === "running";
  const summary = summarize(attachmentLoad?.items ?? []);

  return (
    <Dialog
      open={attachmentLoad !== null}
      disablePointerDismissal
      onOpenChange={(open) => {
        if (open) return;
        if (running) cancel();
        else dismissAttachmentLoad();
      }}
    >
      <DialogContent showCloseButton={false} className="sm:max-w-lg" aria-label="Loading the attachments">
        <DialogHeader>
          <DialogTitle>{running ? "Loading attachments" : "Could not load the attachments"}</DialogTitle>
          <DialogDescription>
            {running
              ? "The server is loading the files named in the request. It is sent as soon as they are all ready."
              : "Nothing was sent. Fix the paths below, or choose the files again, and try again."}
          </DialogDescription>
        </DialogHeader>

        <div className="flex flex-col gap-2">
          <Progress value={summary.percent} label="Attachments loaded" className={running ? "" : "[&>div]:bg-red-500"} />
          <p className="text-xs text-gray-500 dark:text-gray-400" aria-live="polite">
            {summary.finished} of {summary.total} file{summary.total === 1 ? "" : "s"}
            {summary.loaded > 0 ? ` · ${formatBytes(summary.bytes)} loaded` : ""}
            {summary.failed > 0 ? ` · ${summary.failed} failed` : ""}
          </p>
        </div>

        <ul aria-label="Attachments" className="flex max-h-72 flex-col gap-1 overflow-y-auto">
          {(attachmentLoad?.items ?? []).map((item) => (
            <li key={item.cid} className="flex items-start gap-3 rounded-lg bg-gray-50 px-3 py-2 dark:bg-white/3">
              <span className="mt-0.5 flex size-4 shrink-0 items-center justify-center"><StateIcon state={item.state} /></span>
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <code className="truncate text-sm font-medium text-gray-800 dark:text-white/90">cid:{item.cid}</code>
                  {item.state === "done" && item.size !== undefined && (
                    <span className="ms-auto shrink-0 text-xs text-gray-500 dark:text-gray-400">{formatBytes(item.size)}</span>
                  )}
                </div>
                <div className="truncate font-mono text-xs text-gray-500 dark:text-gray-400" title={item.path}>{item.path}</div>
                {item.error && <p className="mt-1 text-xs text-red-600 dark:text-red-400" role="alert">{item.error}</p>}
              </div>
            </li>
          ))}
        </ul>

        <DialogFooter>
          {running ? (
            <Button variant="outline" onClick={cancel}>Cancel</Button>
          ) : (
            <>
              <Button variant="outline" onClick={dismissAttachmentLoad}>Close</Button>
              <Button onClick={() => void send()}>Try again</Button>
            </>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
