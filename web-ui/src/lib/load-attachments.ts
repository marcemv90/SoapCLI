/**
 * Loading the files a request names by path, one by one, before the request is sent. The server opens each file (inside
 * its attachments folder), so a wrong path, a file that is too big or one that left the folder shows up here, with the
 * file's name, instead of as a failed call.
 */

export type LoadState = "pending" | "loading" | "done" | "error";

/** The least time the loading dialog stays up, so that it can be read even when the files load at once. */
export const MIN_VISIBLE_MS = 500;

/** Waits `ms`, or throws the signal's reason if it is aborted first. */
export function pause(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    if (signal?.aborted) return reject(signal.reason);
    const onAbort = () => {
      clearTimeout(timer);
      reject(signal!.reason);
    };
    const timer = setTimeout(() => {
      signal?.removeEventListener("abort", onAbort);
      resolve();
    }, ms);
    signal?.addEventListener("abort", onAbort, { once: true });
  });
}

export interface LoadItem {
  cid: string;
  path: string;
  state: LoadState;
  /** The file's name and size, once the server has found it. */
  name?: string;
  size?: number;
  error?: string;
}

export interface LoadSummary {
  total: number;
  /** Files that have finished, loaded or not. */
  finished: number;
  loaded: number;
  failed: number;
  /** Bytes of the files that loaded. */
  bytes: number;
  /** 0 to 100: how many of the files have finished. */
  percent: number;
}

export function summarize(items: LoadItem[]): LoadSummary {
  const loaded = items.filter((i) => i.state === "done");
  const failed = items.filter((i) => i.state === "error").length;
  const finished = loaded.length + failed;
  return {
    total: items.length,
    finished,
    loaded: loaded.length,
    failed,
    bytes: loaded.reduce((sum, i) => sum + (i.size ?? 0), 0),
    percent: items.length === 0 ? 100 : Math.round((finished / items.length) * 100),
  };
}

export const pendingItems = (files: { cid: string; path: string }[]): LoadItem[] =>
  files.map((f) => ({ cid: f.cid, path: f.path, state: "pending" as const }));

/**
 * Loads each file in turn and reports every change through `onChange`. It goes on after a failure, so that all the
 * problems are shown at once. Throws the signal's reason as soon as `signal` is aborted.
 */
export async function loadAttachments(
  files: { cid: string; path: string }[],
  check: (path: string, signal?: AbortSignal) => Promise<{ name: string; size: number }>,
  onChange: (items: LoadItem[]) => void,
  signal?: AbortSignal,
): Promise<{ ok: boolean; items: LoadItem[] }> {
  const items = pendingItems(files);
  const set = (index: number, change: Partial<LoadItem>) => {
    items[index] = { ...items[index], ...change };
    onChange([...items]);
  };
  onChange([...items]);
  for (let i = 0; i < items.length; i++) {
    signal?.throwIfAborted();
    set(i, { state: "loading" });
    try {
      const found = await check(items[i].path, signal);
      set(i, { state: "done", name: found.name, size: found.size });
    } catch (e) {
      if (signal?.aborted || (e instanceof DOMException && e.name === "AbortError")) throw e;
      set(i, { state: "error", error: e instanceof Error ? e.message : String(e) });
    }
  }
  return { ok: items.every((i) => i.state === "done"), items };
}
