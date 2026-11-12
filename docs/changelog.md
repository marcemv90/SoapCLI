# Changelog

## Unreleased

### Added

- **[Workspaces](workspaces.md)**, to keep things on the server and share them with a team. `serve --workspaces-dir <dir>`
  (`SOAPCLI_WORKSPACES_DIR`) turns every folder inside `<dir>` into a workspace with its own `profiles.json`,
  `placeholders.json`, `attachments/` folder and saved requests (`requests/`), plus an optional `workspace.json`
  (`description`, `readOnly`, `allowedHosts`) and `secrets.env` (the `${NAME}` its profiles use). A relative path in a profile
  starts in the workspace folder, so a workspace is self-contained. Folders added by hand show up without a restart.
  In the UI: a workspace switcher, a **Workspaces** page to create, open and remove them (removing renames the folder to
  `.removed-<name>-<date>`: nothing is deleted, and a `readOnly` workspace cannot be removed), and addresses that name the
  workspace (`#/request?w=billing`). `--workspaces-dir` replaces `--profile-file`, `--placeholders-file` and
  `--attachments-dir`, which are refused next to it on the command line. New API: `/api/workspaces` and everything of a
  workspace under `/api/workspaces/{name}/` (the same endpoints as `/api/`, which keep working on a server without workspaces).
- **A shared library of saved requests.** In a workspace, *Save* can keep a request on the server (one JSON file in
  `requests/`, with an optional *folder* to group them) instead of in the browser: everyone who opens the workspace sees it.
  Opening one remembers its version, and saving over it, or removing it, is refused if someone changed it meanwhile (*Save as
  a new request* is offered). The Library has a link button that copies an address which opens that request in that workspace
  for whoever follows it. Credentials are not stored: not the password or token of the *Auth* tab, nor the value of a header
  or variable whose name looks like one (the save says what it left out). Requests saved in the browser before stay under
  *This browser*, and can be moved to the workspace. The history and the request being edited are kept per workspace.
  New API: `GET /api/workspaces/{name}/requests` (and `/{id}`), `POST .../requests/save` and `.../requests/delete`.
- **`--workspace <name>` and `--workspaces-dir <dir>` for the command line client**: the profiles file, the secrets and the
  relative paths of the profiles are the workspace's, so scripts and the web UI share them. *Copy as…* writes this command
  for a request of a workspace.
- The Docker image has an empty `/workspaces` folder, owned by the unprivileged user; workspaces are not turned on by
  default (`-e SOAPCLI_WORKSPACES_DIR=/workspaces -v "$PWD/workspaces:/workspaces"`). A commented option in `compose.yaml`,
  and [`examples/workspaces`](https://github.com/marcemv90/SoapCLI/tree/main/examples/workspaces), a complete setup to copy.

- **[`examples/docker`](https://github.com/marcemv90/SoapCLI/tree/main/examples/docker)**, a complete Docker setup to copy and edit: a `docker-compose.yml` with the profiles,
  placeholder options, request files, attachments and certificates folder it expects, and a small mock service so that
  everything can be tried before pointing it at a real one. A job of the CI builds the image and runs the example end to
  end, so it does not drift from the code.

### Notes

- Nothing changes for a server started without `--workspaces-dir`: the same files, the same API addresses, and the
  browser keeps its saved requests, history and draft under the same keys.
- Who can do what in a workspace: saving and removing requests only needs a writable folder, a workspace that is not
  `readOnly`, and a server that was not started with `--no-config-edit`; **it does not need `--allow-config-edit`**, because
  sharing requests is what workspaces are for. Editing profiles and placeholder options, and creating and removing
  workspaces, follow the rule that was already there (on by default only when the server listens on this machine).
- The interface's internal state module is now `state/app.tsx` (`useApp`), so that "workspace" only means the new thing.

## 2.2.0

The web UI grows: profiles and the options of the placeholders can be edited from it, attachments can be saved with a
request by their path, and every call goes through the SoapCLI server. The command line client is unchanged.

### Changed

- **The web UI always sends through the SoapCLI server.** The *This browser* / *SoapCLI server* choice is gone: calling
  from the browser added nothing that the server cannot do, it needed the service to allow CORS, and it forced the UI to
  carry a second copy of the request builder. The server's client certificates, proxies and profiles are now always
  available, and there is no CORS to diagnose. A page opened without a SoapCLI server (a copy of the files) now says so and
  cannot send. The *Settings* option "Make calls from" is removed, and old saved drafts that mention it still load.
- **Variables and attachments are on the Body screen**, under the editor, instead of in tabs of their own. Attachments are
  detected from the request: every `cid:NAME` written as the **text** of an element is listed and you choose its file
  (button or drag and drop). A `cid:` inside an **attribute** is not an attachment, so in
  `<payload payloadId="cid:message"><value>cid:1234</value></payload>` only `1234` is listed. A file whose `cid:` is no
  longer in the body is kept in memory but not sent.
- The sidebar no longer shows the "SoapCLI server" box; the details are in *Settings* (and a notice appears on the request
  page only if the server cannot be reached).
- The *Sample* button of the editor is removed.
- The server's `Content-Security-Policy` is now `connect-src 'self'`: the page only talks to its own server.
- *Copy as → curl* is built from the request as the server builds it (so it also works with profiles); headers that come
  from a profile are left as `****` to be filled in.

### Added

- **Edit profiles from the web UI.** The *Profiles* page can create, edit and delete profiles, with a form for every
  setting. It edits what the profile itself says (what it inherits shows as *inherited: …* in the empty fields, and an
  inherited proxy or certificate can be switched off for the profile), keeps the settings the form does not know, and never
  sees a secret: a saved one shows as *saved* and is kept unless a new value is typed. Only the edited profile is rewritten:
  the rest of the file, its formatting and its comments stay byte for byte (the file is replaced atomically and keeps its
  permissions and symbolic link). A save quotes the revision of the file it was made from, so a change made meanwhile is
  never overwritten; a profile that would not work, or that would break one that extends it, is refused. **Who can edit:**
  on by default only when the server listens on this machine; on a network address (Docker included) it needs the new
  `--allow-config-edit` (`SOAPCLI_ALLOW_CONFIG_EDIT=true`), and `--no-config-edit` switches it off. The file must be
  writable (with Docker, mount `/config` without `:ro`). See [Editing profiles](web-ui.md#editing-profiles).
- **Dropdowns for placeholders.** A placeholder can have **options**, kept in a file of its own
  (`~/.config/soap-cli-placeholders.json`; `--placeholders-file`, `SOAPCLI_PLACEHOLDERS_FILE`; `/config/placeholders.json`
  in the Docker image) because they belong to the placeholders, not to a profile. Its variable on the request page becomes a
  dropdown: the request's own default, each option (optionally with a label), and *Custom value…*. The new
  **Placeholders** page edits the file, with the same rules as the profiles (who can edit, the revision check, the rest of
  the file left alone), and a variable without options offers *Give it options*. The command line does not read this file.
  See [Placeholder options](web-ui.md#placeholder-options). New API: `GET /api/placeholders`, `POST /api/placeholders/save`
  and `/delete`, `GET /api/profiles/{name}/definition`, `POST /api/profiles/save` and `/delete`.
- **Attachments by path.** An attachment can be named by its **path on the server** instead of being a file picked in the
  browser, and only the path is saved with the request (Library, History, the draft), so a request with attachments can be
  saved and sent again. Each `cid:` row has a path field that checks the file as you type. When you press *Send*, a dialog
  loads the files one by one with their progress, and the request is sent when all of them load; if one cannot be loaded
  nothing is sent and the dialog says why (*Close*, *Try again*, or *Cancel* / <kbd>Esc</kbd> to stop). A file chosen on
  this computer still works and is still never saved. `serve` has a new option, `--attachments-dir`
  (`SOAPCLI_ATTACHMENTS_DIR`): the server reads only inside that folder (default: its working directory, `/work` in the
  Docker image), so the UI cannot be used to send arbitrary files of the machine; `..`, absolute paths elsewhere and symbolic
  links that leave the folder are refused. New API: `POST /api/attachments/check`, and `path` next to `contentBase64` in the
  attachments of `/api/send` and `/api/preview`; `/api/info` reports `attachmentsDir`. See
  [Attachments by path](web-ui.md#attachments-by-path).
- **Layout of the request page**: request and response side by side (as before) or stacked, with the response under the
  request. Choose it next to *New* or in *Settings*; it is remembered.
- **Profiles page**: the configuration of each server profile as the server resolves it (after `extends`, secrets masked,
  `${VAR}` shown as written), as a table and as JSON, with *Use in the request* and *Load its request file*. Also reachable
  with *View profile* next to the profile selector.

### Fixed

- The web server now reads and discards what is left of a request body that is too large before answering `413`. Closing
  the connection with unread data could make the operating system reset it and destroy the answer, so the browser showed
  a network error instead of "The request is larger than 25 MB (--max-body-mb)".

### Notes

- Existing `serve` command lines and Docker commands keep working; every new option is optional.
- The Docker image now sets `SOAPCLI_PLACEHOLDERS_FILE=/config/placeholders.json` and `SOAPCLI_ATTACHMENTS_DIR=/work`. To
  attach files by path, mount them under `/work`; to edit profiles and placeholder options from the UI, mount `/config`
  without `:ro` and set `SOAPCLI_ALLOW_CONFIG_EDIT=true` (inside a container the server listens on a network address, so
  editing is off by default).
- The jar and the Docker image take their version from the tag, as for the earlier releases.

## 2.1.0

A web UI and a Docker image. The command line client is unchanged.

### Added

- **[Web UI](web-ui.md)** inside the same JAR: `java -jar SoapCLI.jar serve`. Built with React and the TailAdmin
  template; nothing is loaded from the internet. XML editor, headers, Basic/Bearer authentication, `___NAME___`
  variables, **attachments uploaded from the browser** (inline Base64 or MTOM), SOAP 1.1/1.2 with the SOAP action, a
  formatted response with headers and the exact request that was built, history and a saved library, light and dark
  themes, and *Copy as* SoapCLI command, Docker command or `curl`.
- **Two ways to send**: from **your browser** (default; reaches anything your machine reaches, needs the service to allow
  CORS, and the UI explains and diagnoses CORS failures) or through the **SoapCLI server** (client certificates, trust
  stores, proxies, skipping TLS checks, server [profiles](cli-profiles.md) with their secrets kept on the server).
- **`serve`** options for shared deployments: a login (`--ui-user`, `--ui-password` / `--ui-password-env`), an allow-list of
  destinations (`--allow-host`, also checked on redirects), `--no-insecure-tls`, `--max-body-mb`, `--max-response-mb`,
  and the equivalent `SOAPCLI_*` environment variables. `healthcheck` for container probes.
- **[Docker image](docker.md)**: the UI by default, the command line client with any other arguments
  (`docker run --rm -v "$PWD:/work" IMAGE --endpoint ... --request-file request.xml`), non-root, with a health check,
  `/config` for profiles and `/work` for files; a `compose.yaml`; published to `ghcr.io` on each release.
- `cli` subcommand (`docker run IMAGE cli --version`) to select the command line client explicitly.
- Core: `HostPolicy` (destination allow-list) and a maximum response size, used by the web server.
- `THIRD_PARTY_NOTICES.md` with the licences of the bundled interface.

### Notes

- Starting the JAR with `serve`, `healthcheck` or `cli` as the first argument now means something; no existing option
  starts with those words, so existing command lines are not affected.
- The UI is compiled and committed (`src/main/resources/web`), so building the JAR still needs only a JDK and Maven.
- The Docker image is published for `linux/amd64` and `linux/arm64`, so it also runs on Macs with Apple Silicon and ARM
  servers. (The first publication of `2.1.0` contained only `amd64`; it was replaced by the multi-architecture image.)

## 2.0.0

A large clean-up release: the tool was rewritten into a tested core plus a thin command line layer, several things that
did not work were fixed, and a few behaviours changed on purpose. **Read "Behaviour changes" before upgrading scripts.**

!!! note "Why 2.0.0?"
    The exit codes changed (see below), and the first version documented that they mirror the HTTP status, so scripts
    that rely on that break. That makes this a major version. The same code was briefly published as 1.1.0; if you
    downloaded that build, use 2.0.0 instead.

### Behaviour changes

| Before | Now | What to do |
|--------|-----|------------|
| Exit code = HTTP status (so `500` → `244` on Linux/macOS) | `0` = 2xx, `1` = usage/config error, `2` = network error, `3` = other HTTP status. Status printed on stderr | Test for `3`, read the status from stderr ([details](cli-rc-and-errors.md)) |
| Redirects followed silently (a `302` turned the POST into a GET) | Not followed; reported with exit code 3. `--follow-redirects` re-sends the POST | Fix the endpoint, or add `--follow-redirects` |
| Colour always on | Colour only on a terminal (`--color` forces it; `NO_COLOR` is honoured) | Add `--color` if you relied on colour in pipes |
| Every message was `application/soap+xml` | SOAP 1.1 envelopes are sent as `text/xml` with a `SOAPAction` header | Usually nothing: this fixes SOAP 1.1 services. Force with `--soap-version` if needed |
| MTOM always sent `action="submitMessage"` and a `rootpart@soapui.org` content-id | The action comes from `--soap-action` (SOAP 1.2); no hard-coded value | For Domibus add `--soap-action submitMessage` |
| Read timeout 30 s | Read timeout 120 s; `--timeout` / `--connect-timeout` | — |
| Response was re-serialised through a DOM (`standalone="no"` added, entities rewritten) and zero-width characters were removed | Only whitespace *between* elements is changed; the rest is as the server sent it | `--raw` for the exact bytes |
| Output had no final newline | Output ends with a newline | — |
| Placeholder names could be anything up to the next `:`; values were trimmed; `--arg X=` was rejected | Names use letters, digits, `_ . -`; values are kept as written; `--arg X=` is allowed | — |
| `--keystore-type` defaulted to `JKS`; `--keystore-alias` was mandatory | Type inferred from the extension; alias optional with a single key | — |
| Stack traces on every error | One-line `Error:` message; stack traces with `--debug` | — |
| `--dry-run` / `--debug` printed `Authorization` in clear | Masked; `--show-secrets` to reveal | — |
| Status was not shown | `HTTP 200 OK (143 ms, 312 bytes)` on stderr | `-q` hides it |
| Main class `SoapCLI` | `io.github.marcemv90.soapcli.cli.Main` | Keep using `java -jar` |

### Fixed

- **`--header` failed on every call** with `IllegalStateException: Already connected`, including the documented
  `SOAPAction` example. Headers are now set before the body is written.
- **XML External Entity (XXE) exposure**: a response containing a `<!DOCTYPE ... <!ENTITY x SYSTEM "file:///...">>`
  made the formatter read local files (or contact other hosts) and print the result. The formatter no longer processes
  DTDs at all.
- **Character encodings**: responses in ISO-8859-1 (or any non-UTF-8 charset) were corrupted; requests declared as
  ISO-8859-1 were re-encoded as UTF-8 under a wrong prolog; a UTF-8 BOM in the request file broke `--attachment`.
- **MTOM sent the wrong attachment** when one CID was a prefix of another (`message1` / `message10`).
- **Slow stdin producers** (`slow-command | SoapCLI`) failed with "stdin is empty".
- **Placeholders**: a stray `___` in the document could swallow XML.
- **Proxy authentication** (`Basic`) did not work for HTTPS endpoints because the JDK disables it on tunnels by default.
- `file://` and other non-HTTP endpoints crashed with a `ClassCastException`; they now give a clear message.
- `--help`/`-h` anywhere on the line (even as a header value) used to short-circuit parsing.
- `--help` prints to stdout (exit code 0); no arguments prints usage to stderr (exit code 1).

### Added

- SOAP 1.1 / 1.2 detection, `--soap-version`, `--soap-action`.
- `--timeout`, `--connect-timeout`, `--follow-redirects`.
- `--auth-bearer`, `--auth-bearer-env`.
- TLS: `--truststore`, `--truststore-type`, `--truststore-password`, `--insecure`; the keystore alias is optional when
  the keystore has one key, aliases are validated and listed on mismatch.
- `*-env` variants for secrets: `--keystore-password-env`, `--keystore-alias-pkpass-env`, `--truststore-password-env`,
  `--proxy-password-env`.
- Output: `--raw`, `-o`/`--output`, `-i`/`--include`, `-q`/`--quiet`, `--show-secrets`, `--version`.
- `--arg-text` (XML-escaped placeholder values); warnings for unused `--arg`s, unreferenced attachments, and an
  undetectable SOAP version.
- `--request-file -` for stdin; `--option=value` syntax.
- Profiles: `headers`, `authBasic`, `authBearer`, `args`, `attachments`, `soapVersion`, `soapAction`, `mtom`, `timeout`,
  `connectTimeout`, `followRedirects`, `truststore`, `insecure`; `extends` inheritance; `${ENV}` references; comments in
  the JSON; `~` expansion; unknown keys reported; `SOAPCLI_PROFILE_FILE`; `--show-profile`.
- `Host` and other headers the JDK normally drops can now be set with `--header`.
- MTOM attachments are streamed from disk; large bodies are streamed rather than buffered.

### Project

- Split into `core` and `cli` packages (see [Architecture](architecture.md)).
- 150+ JUnit 5 tests, including real TLS, mutual TLS and an authenticating proxy on local sockets.
- Java 11 enforced with `maven.compiler.release`; CI builds on Java 11, 17 and 21 on Linux and Windows.
- The version is stamped into the JAR (`--version`); releases publish a SHA-256 checksum.
- Documentation updated; Jekyll leftovers and unused assets removed.

## 1.0.0

First public version.
