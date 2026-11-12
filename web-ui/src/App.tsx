import { AppShell } from "@/components/layout/AppShell";
import { LibraryPage } from "@/pages/LibraryPage";
import { PlaceholdersPage } from "@/pages/PlaceholdersPage";
import { ProfilesPage } from "@/pages/ProfilesPage";
import { RequestPage } from "@/pages/RequestPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { WorkspacesPage } from "@/pages/WorkspacesPage";
import { ThemeProvider } from "@/state/theme";
import { AppProvider, useApp } from "@/state/app";

function Pages() {
  const { page, workspace, workspacesEnabled, server } = useApp();
  // A server with workspaces but none yet has nothing to send from: the way out is to make one.
  const needsWorkspace = workspacesEnabled && workspace === null && server.loaded;
  const shown = needsWorkspace && page !== "settings" ? "workspaces" : page;
  return (
    <AppShell>
      {/* The request page stays mounted so that switching pages never loses the editor's state. */}
      <div hidden={shown !== "request"}><RequestPage /></div>
      {shown === "library" && <LibraryPage />}
      {shown === "profiles" && <ProfilesPage />}
      {shown === "placeholders" && <PlaceholdersPage />}
      {shown === "workspaces" && <WorkspacesPage />}
      {shown === "settings" && <SettingsPage />}
    </AppShell>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <AppProvider>
        <Pages />
      </AppProvider>
    </ThemeProvider>
  );
}
