import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import path from "node:path";
import { defineConfig } from "vitest/config";

// The build goes straight into the Java project's resources, so that the jar embeds the UI.
export default defineConfig({
  // Relative URLs: the UI works from any path (for instance behind a reverse proxy under /soapcli/).
  base: "./",
  plugins: [react(), tailwindcss()],
  resolve: { alias: { "@": path.resolve(import.meta.dirname, "./src") } },
  build: {
    outDir: "../src/main/resources/web",
    emptyOutDir: true,
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
  },
  // `npm run dev`: talk to a SoapCLI server started with `java -jar SoapCLI.jar serve`.
  server: { proxy: { "/api": "http://127.0.0.1:8080", "/healthz": "http://127.0.0.1:8080" } },
  test: { environment: "node", include: ["test/**/*.test.ts"] },
});
