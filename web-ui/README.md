# SoapCLI web UI

The browser client of SoapCLI. It is compiled into `../src/main/resources/web`, which the Maven build packs into the jar,
so the jar needs no Node and no network. Users' documentation: [docs/web-ui.md](../docs/web-ui.md).

## Stack

React 19, TypeScript, Vite 7, Tailwind CSS 4, the free [TailAdmin](https://tailadmin.com) React template (MIT; the pieces
used live in `src/components/ui`, `src/icons` and `src/index.css`), `@base-ui/react` primitives, CodeMirror for the XML
editor, and the Outfit font bundled with `@fontsource-variable/outfit`. Nothing is fetched from a CDN at run time.
Licences: [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).

## Commands

```bash
npm ci               # install exactly what package-lock.json says
npm run dev          # http://localhost:5173; /api and /healthz are proxied to http://127.0.0.1:8080
npm test             # Vitest (the browser engine)
npm run typecheck
npm run build        # typecheck, then build into ../src/main/resources/web
```

For `npm run dev` to have a back end, run `java -jar ../target/SoapCLI.jar serve` (or `mvn -q package -DskipTests` first).
Without it the UI still works in *This browser* mode.

## After changing the UI

Run `npm run build` and **commit the changes under `src/main/resources/web`**. The output is deterministic, and CI rebuilds
it and fails if it differs from what is committed (`git status --porcelain`). If it only differs by a hash, you forgot to
rebuild or used another `npm ci`.

## Layout

```text
src/
  main.tsx, App.tsx          entry, hash routing (#/request, #/library, #/settings)
  pages/                     RequestPage, LibraryPage, SettingsPage
  components/
    layout/AppShell.tsx      sidebar, header, theme toggle
    request/                 EndpointBar, tabs (body, headers, auth, variables, attachments, options), ResponsePanel
    common/                  CodeEditor (CodeMirror), small controls
    ui/                      TailAdmin components
  state/                     workspace (draft, response, history, saved, settings, server info), theme
  lib/                       the engine, in TypeScript
    xml.ts, placeholders.ts, soap.ts, charset.ts, cid.ts      ports of the Java core
    request.ts               builds the request exactly as core/RequestBuilder does
    transports/browser.ts    fetch, timeouts, redirects, CORS diagnosis
    transports/server.ts     /api/send, /api/preview, /api/format, /api/info, /api/profiles
    command.ts               "Copy as" (SoapCLI, Docker, curl)
    storage.ts, mask.ts      what is kept in localStorage (never secrets or attachment contents)
test/                        Vitest suites
public/                      favicon and theme-init.js (sets the theme before the first paint)
```

## Rules of the road

- **Keep the browser engine and `core` in step.** `lib/request.ts` and friends port the Java behaviour (placeholders, SOAP
  version detection, charset handling, CID replacement, MTOM bytes). If you change one, change the other and their tests.
- **Relative URLs only** (`base: './'`, hash routing), so the UI works behind a path prefix and from static files.
- **No inline scripts, no external hosts**: the server sends `script-src 'self'`.
- **No secrets in storage**: passwords, tokens and attachment contents stay in memory.
- Keep `Content-Type: application/json` on every API call; the server refuses anything else (CSRF defence).
