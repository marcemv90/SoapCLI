import { useEffect, useState, type ReactNode } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { CodeIcon, FolderIcon, GearIcon, MenuTwoIcon, MoonIcon, SunIcon, XIcon } from "@/icons";
import { useTheme } from "@/state/theme";
import { useWorkspace, type Page } from "@/state/workspace";
import { cn } from "@/utils";

const NAV: { page: Page; label: string; icon: ReactNode }[] = [
  { page: "request", label: "Request", icon: <CodeIcon /> },
  { page: "library", label: "Library", icon: <FolderIcon /> },
  { page: "settings", label: "Settings", icon: <GearIcon /> },
];

const TITLES: Record<Page, string> = { request: "Request", library: "Library", settings: "Settings" };

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

function ServerWidget() {
  const { server, navigate } = useWorkspace();
  return (
    <div className="rounded-2xl bg-gray-50 px-4 py-5 dark:bg-white/3">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-sm font-semibold text-gray-800 dark:text-white/90">SoapCLI server</span>
        {!server.loaded ? null : server.info
          ? <Badge variant="light" color="success" size="sm">connected</Badge>
          : <Badge variant="light" color="gray" size="sm">not available</Badge>}
      </div>
      <p className="mb-3 text-xs text-gray-500 dark:text-gray-400">
        {server.info
          ? `Version ${server.info.version}. Use it for client certificates, proxies and servers that block cross-origin calls.`
          : "Calls are made straight from this browser. Run this page from a SoapCLI server to also send through it."}
      </p>
      <button type="button" onClick={() => navigate("settings")} className="text-xs font-medium text-brand-500 hover:underline dark:text-brand-400">
        Details
      </button>
    </div>
  );
}

function Sidebar({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { page, navigate } = useWorkspace();
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
        <nav className="flex-1 overflow-y-auto">
          <h2 className="mb-4 px-3 text-xs leading-5 font-medium text-gray-400 uppercase">Menu</h2>
          <ul className="flex flex-col gap-1">
            {NAV.map((item) => {
              const active = page === item.page;
              return (
                <li key={item.page}>
                  <a
                    href={`#/${item.page}`}
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
        <div className="pb-6"><ServerWidget /></div>
      </aside>
    </>
  );
}

function Header({ onMenu }: { onMenu: () => void }) {
  const { page } = useWorkspace();
  const { resolved, toggle } = useTheme();
  return (
    <header className="sticky top-0 z-50 flex w-full items-center justify-between gap-3 border-b border-gray-200 bg-white px-4 py-3 md:px-6 dark:border-gray-800 dark:bg-gray-900">
      <div className="flex items-center gap-3">
        <Button variant="outline" size="icon" className="text-gray-500 xl:hidden dark:text-gray-400" onClick={onMenu} aria-label="Open menu">
          <MenuTwoIcon className="size-6" />
        </Button>
        <h1 className="text-lg font-semibold text-gray-800 dark:text-white/90">{TITLES[page]}</h1>
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
