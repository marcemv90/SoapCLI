/**
 * Where the page is, kept in the URL hash so that the server needs no routing and a link can be shared:
 *
 *   #/library?w=billing                    a page, in a workspace
 *   #/request?w=billing&open=get-order-k3  ...and a saved request of the workspace to open on it
 */
export type Page = "request" | "library" | "profiles" | "placeholders" | "workspaces" | "settings";

const PAGES: readonly Page[] = ["request", "library", "profiles", "placeholders", "workspaces", "settings"];

/** The same shape of name the server accepts for a workspace (a folder name). */
export const WORKSPACE_NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;

export interface Route {
  page: Page;
  /** The workspace the link is about, or `null`. */
  workspace: string | null;
  /** The id of a saved request to open, or `null`. */
  open: string | null;
}

export function parseRoute(hash: string): Route {
  const text = hash.replace(/^#\/?/, "");
  const question = text.indexOf("?");
  const path = question < 0 ? text : text.slice(0, question);
  const query = new URLSearchParams(question < 0 ? "" : text.slice(question + 1));
  const w = query.get("w");
  const open = query.get("open");
  return {
    page: (PAGES as readonly string[]).includes(path) ? (path as Page) : "request",
    workspace: w !== null && WORKSPACE_NAME.test(w) ? w : null,
    open: open !== null && open !== "" && open.length <= 120 ? open : null,
  };
}

export function buildHash(route: { page: Page; workspace?: string | null; open?: string | null }): string {
  const query = new URLSearchParams();
  if (route.workspace) query.set("w", route.workspace);
  if (route.open) query.set("open", route.open);
  const text = query.toString();
  return `#/${route.page}${text ? `?${text}` : ""}`;
}
