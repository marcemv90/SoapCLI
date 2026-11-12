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
npm test             # Vitest (the helpers the page computes itself, the server API client, "Copy as")
npm run typecheck
npm run build        # typecheck, then build into ../src/main/resources/web
```

For `npm run dev` to have a back end, run `java -jar ../target/SoapCLI.jar serve` (or `mvn -q package -DskipTests` first).
Without it the page loads but cannot send anything: every call goes through the server.

## After changing the UI

Run `npm run build` and **commit the changes under `src/main/resources/web`**. The output is deterministic, and CI rebuilds
it and fails if it differs from what is committed (`git status --porcelain`). If it only differs by a hash, you forgot to
rebuild or used another `npm ci`.

## Layout

```text
src/
  main.tsx, App.tsx          entry, hash routing (#/request, #/library, #/profiles, #/placeholders, #/settings)
  pages/                     RequestPage, LibraryPage, ProfilesPage, PlaceholdersPage, SettingsPage
  components/
    layout/AppShell.tsx      sidebar, header, theme toggle
    request/                 EndpointBar, body (editor, variables, attachments), tabs (headers, auth, options), ResponsePanel,
                             AttachmentLoadDialog
    profiles/                ProfileForm and its form controls (fields, secrets, name/value tables)
    common/                  CodeEditor (CodeMirror), small controls
    ui/                      TailAdmin components
  state/                     workspace (draft, response, history, saved, settings, server info), theme
  lib/                       helpers and the API client, in TypeScript
    xml.ts, placeholders.ts, soap.ts, cid.ts      small ports of the Java core (scanner, variable listing, version badge,
                             `cid:` references of the attachments list)
    attachments.ts           which assigned files are sent: the ones whose `cid:` the body still refers to
    load-attachments.ts      loads the files named by path, one by one, before sending (drives the progress dialog)
    profile-form.ts          the model of the profile form: a profile as the JSON it is, edited by path; secrets are `****`
    placeholder-options.ts   the rows of the placeholder options editor, pasted lists
    transports/server.ts     /api/send, /api/preview, /api/format, /api/info, /api/profiles, /api/placeholders (and their edits)
    command.ts               "Copy as" (SoapCLI, Docker, curl)
    storage.ts               what is kept in localStorage (never secrets or attachment contents)
test/                        Vitest suites
public/                      favicon and theme-init.js (sets the theme before the first paint)
```

## Rules of the road

- **The server does the work; keep the small ports in step with `core`.** The page does not build requests: it sends the
  form to the API. What it computes itself for display (`xml.ts`, `placeholders.ts`, `soap.ts`, `cid.ts`) ports Java
  behaviour (`XmlScanner`, `Placeholders`, `SoapVersion`, `CidReplacer`). If you change one, change the other and their tests.
- **Relative URLs only** (`base: './'`, hash routing), so the UI works behind a path prefix.
- **The page may only call its own server** (`connect-src 'self'`): never `fetch` another host from the UI.
- **No inline scripts, no external hosts**: the server sends `script-src 'self'`.
- **No secrets in storage**: passwords, tokens and attachment contents stay in memory.
- Keep `Content-Type: application/json` on every API call; the server refuses anything else (CSRF defence).
