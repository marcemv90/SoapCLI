import { AppShell } from "@/components/layout/AppShell";
import { LibraryPage } from "@/pages/LibraryPage";
import { PlaceholdersPage } from "@/pages/PlaceholdersPage";
import { ProfilesPage } from "@/pages/ProfilesPage";
import { RequestPage } from "@/pages/RequestPage";
import { SettingsPage } from "@/pages/SettingsPage";
import { ThemeProvider } from "@/state/theme";
import { WorkspaceProvider, useWorkspace } from "@/state/workspace";

function Pages() {
  const { page } = useWorkspace();
  return (
    <AppShell>
      {/* The request page stays mounted so that switching pages never loses the editor's state. */}
      <div hidden={page !== "request"}><RequestPage /></div>
      {page === "library" && <LibraryPage />}
      {page === "profiles" && <ProfilesPage />}
      {page === "placeholders" && <PlaceholdersPage />}
      {page === "settings" && <SettingsPage />}
    </AppShell>
  );
}

export default function App() {
  return (
    <ThemeProvider>
      <WorkspaceProvider>
        <Pages />
      </WorkspaceProvider>
    </ThemeProvider>
  );
}
