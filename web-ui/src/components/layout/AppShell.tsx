import { useEffect, useState, type ReactNode } from "react";
import { NativeSelect } from "@/components/common/controls";
import { Button } from "@/components/ui/button";
import { CodeIcon, FolderIcon, GearIcon, LayoutGridIcon, ListIcon, MenuTwoIcon, MoonIcon, SunIcon, TagIcon, XIcon } from "@/icons";
import { buildHash } from "@/lib/route";
import { useTheme } from "@/state/theme";
import { useApp, type Page } from "@/state/app";
import { cn } from "@/utils";

const NAV: { page: Page; label: string; icon: ReactNode; onlyWithWorkspaces?: boolean }[] = [
  { page: "request", label: "Request", icon: <CodeIcon /> },
  { page: "library", label: "Library", icon: <FolderIcon /> },
  { page: "profiles", label: "Profiles", icon: <TagIcon /> },
  { page: "placeholders", label: "Placeholders", icon: <ListIcon /> },
  { page: "workspaces", label: "Workspaces", icon: <LayoutGridIcon />, onlyWithWorkspaces: true },
  { page: "settings", label: "Settings", icon: <GearIcon /> },
];

const TITLES: Record<Page, string> = {
  request: "Request", library: "Library", profiles: "Profiles", placeholders: "Placeholders", workspaces: "Workspaces", settings: "Settings",
};

function Logo() {
  return (
    <div className="flex items-center gap-3">
      <img src="./favicon.svg" alt="" className="size-9" />
      <div className="leading-tight">
        <div className="text-xl font-semibold text-gray-800 dark:text-white/90">SoapCLI</div>
        <div className="text-xs text-gray-500 dark:text-gray-400">SOAP client</div>
      </div>
    </div>
  );
}

/** Which workspace the profiles, saved requests and attachments are those of. Only on a server that has workspaces. */
function WorkspaceSwitcher({ onDone }: { onDone: () => void }) {
  const { workspace, server, switchWorkspace, navigate } = useApp();
  if (!server.workspaces?.enabled) return null;
  const list = server.workspaces.workspaces;
  const current = list.find((w) => w.name === workspace);
  return (
    <div className="mb-6 px-3">
      <label htmlFor="workspace-select" className="mb-1.5 block text-xs leading-5 font-medium text-gray-400 uppercase">Workspace</label>
      {list.length === 0 ? (
        <button type="button" onClick={() => { navigate("workspaces"); onDone(); }} className="text-sm font-medium text-brand-500 hover:underline dark:text-brand-400">
          No workspaces yet
        </button>
      ) : (
        <NativeSelect id="workspace-select" className="h-10 w-full" value={workspace ?? ""}
          onChange={(e) => { void switchWorkspace(e.target.value); onDone(); }}>
          {list.map((w) => <option key={w.name} value={w.name}>{w.name}{w.readOnly ? " (read-only)" : ""}</option>)}
        </NativeSelect>
      )}
      {current?.description && <p className="mt-1.5 line-clamp-2 text-xs text-gray-500 dark:text-gray-400" title={current.description}>{current.description}</p>}
    </div>
  );
}

function Sidebar({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { page, navigate, workspace, workspacesEnabled } = useApp();
  return (
    <>
      {open && <div className="fixed inset-0 z-9999 bg-gray-900/50 xl:hidden" onClick={onClose} aria-hidden />}
      <aside
        aria-label="Main navigation"
        className={cn(
          "fixed inset-y-0 left-0 z-99999 flex w-[290px] flex-col border-r border-gray-200 bg-white px-5 transition-transform duration-300 ease-in-out dark:border-gray-800 dark:bg-gray-900",
          "xl:sticky xl:top-0 xl:h-screen xl:translate-x-0 xl:shrink-0",
          open ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex items-center justify-between py-8">
          <Logo />
          <Button variant="ghost" size="icon-sm" className="xl:hidden" onClick={onClose} aria-label="Close menu"><XIcon /></Button>
        </div>
        <nav className="flex-1 overflow-y-auto pb-6">
          <WorkspaceSwitcher onDone={onClose} />
          <h2 className="mb-4 px-3 text-xs leading-5 font-medium text-gray-400 uppercase">Menu</h2>
          <ul className="flex flex-col gap-1">
            {NAV.filter((item) => !item.onlyWithWorkspaces || workspacesEnabled).map((item) => {
              const active = page === item.page;
              return (
                <li key={item.page}>
                  <a
                    href={buildHash({ page: item.page, workspace })}
                    aria-current={active ? "page" : undefined}
                    onClick={(e) => { e.preventDefault(); navigate(item.page); onClose(); }}
                    className={cn("menu-item group", active ? "menu-item-active" : "menu-item-inactive")}
                  >
                    <span className={cn("[&_svg]:size-6", active ? "menu-item-icon-active" : "menu-item-icon-inactive")}>{item.icon}</span>
                    {item.label}
                  </a>
                </li>
              );
            })}
          </ul>
        </nav>
      </aside>
    </>
  );
}

function Header({ onMenu }: { onMenu: () => void }) {
  const { page, workspace } = useApp();
  const { resolved, toggle } = useTheme();
  return (
    <header className="sticky top-0 z-50 flex w-full items-center justify-between gap-3 border-b border-gray-200 bg-white px-4 py-3 md:px-6 dark:border-gray-800 dark:bg-gray-900">
      <div className="flex items-center gap-3">
        <Button variant="outline" size="icon" className="text-gray-500 xl:hidden dark:text-gray-400" onClick={onMenu} aria-label="Open menu">
          <MenuTwoIcon className="size-6" />
        </Button>
        <h1 className="text-lg font-semibold text-gray-800 dark:text-white/90">
          {TITLES[page]}
          {workspace && page !== "workspaces" && <span className="ms-2 text-sm font-normal text-gray-500 dark:text-gray-400">· {workspace}</span>}
        </h1>
      </div>
      <div className="flex items-center gap-2">
        <Button variant="outline" size="icon" onClick={toggle} aria-label={resolved === "dark" ? "Switch to light theme" : "Switch to dark theme"}>
          {resolved === "dark" ? <SunIcon className="size-5" /> : <MoonIcon className="size-5" />}
        </Button>
      </div>
    </header>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setOpen(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open]);
  return (
    <div className="min-h-screen xl:flex">
      <Sidebar open={open} onClose={() => setOpen(false)} />
      <div className="flex min-w-0 flex-1 flex-col">
        <Header onMenu={() => setOpen(true)} />
        <main className="mx-auto w-full max-w-(--breakpoint-2xl) flex-1 p-4 md:p-6">{children}</main>
      </div>
    </div>
  );
}
