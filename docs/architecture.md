# Architecture

Notes for contributors: how the engine, the command line and the web UI fit together.

## Layout

```text
io.github.marcemv90.soapcli
├── core   the engine. No System.out/err, no System.exit, no terminal knowledge.
│   ├── CallConfig, TransportOptions, TlsConfig, ProxyConfig   what the user asked for (plain fields)
│   ├── HostPolicy                                             allow-list of destination hosts (checked on every hop)
│   ├── Profiles, Profile                                      named presets: extends, ${ENV}, masking
│   ├── JsonSplice                                             edits one entry of a JSON file in place (comments kept)
│   ├── RequestBuilder → PreparedRequest                       placeholders, SOAP version, attachments, headers
│   ├── SoapClient → SoapResponse                              HTTP(S) transport, redirects, errors
│   ├── TlsSupport, ProxySupport                               key/trust stores, --insecure, proxy auth
│   ├── MultipartBody, CidReplacer                             MTOM/XOP and attachments
│   └── XmlScanner, XmlFormat, XmlCharsets, Placeholders ...   XML helpers (no DOM, no DTDs)
├── cli    the command line front end
│   ├── Main, Cli                                              wiring and the run loop (Cli returns the exit code)
│   ├── Options, OptionSpec, ArgParser                         one option table drives parsing AND --help
│   └── ResponsePrinter, RequestPreview, AnsiColorizer ...     presentation
└── web    the web server behind `serve`
    ├── ServeCommand, ServeOptions                             `serve` / `healthcheck`: options, env defaults, banner
    ├── WebServer                                              JDK com.sun.net.httpserver: routing, security headers,
    │                                                          login, static files from the classpath (/web)
    ├── ConfigFile                                             a file the UI may edit: revision, writability, safe write
    ├── PlaceholderOptions                                     the dropdown options of the placeholders (own file)
    └── ApiService, ApiModel                                   the JSON API over the engine, and its DTOs

web-ui/                       the interface: React + TypeScript + Tailwind + TailAdmin; builds into
                              src/main/resources/web, which the jar embeds
```

The dependency only goes one way: `cli` and `web` use `core`, never the opposite, and `cli` and `web` do not know each
other (`Main` starts one or the other).

## The flow of one call

```text
CallConfig  ──(Profile.applyTo, CLI options)──►  CallConfig (final)
request text ─┐
              ├─► RequestBuilder.build ─► PreparedRequest ─► SoapClient.send ─► SoapResponse
CallConfig  ──┘        (pure: no network)     (what is previewed          (raw bytes: the caller
                                              is what is sent)             decides how to decode/show)
```

`PreparedRequest` carries warnings instead of printing them, `SoapResponse` carries bytes instead of text, and errors are
typed (`ConfigException` = the user can fix it locally, `TransportException` = the network said no). That keeps every
decision about *presentation* in the front end.

## Design rules

- `core` never prints and never exits. Anything it wants to tell the user goes in a return value or an exception.
- XML is handled as text through `XmlScanner`. There is no DOM or `Transformer`, so DTDs and entities are never
  processed (no XXE) and nothing outside the parts we mean to edit is ever rewritten.
- A preview must be built from the object that is sent.
- Secrets are masked by default wherever they could be displayed (`SecretMasker`).
- Everything the front end needs from the outside world (streams, environment, home directory, terminal) is passed to
  `Cli`'s constructor, so tests run it in-process.

## Using the engine from Java

```java
CallConfig cfg = new CallConfig();
cfg.endpoint = "https://example.com/soap";
cfg.authBasic = "user:password";
cfg.transport.tls.truststorePath = "corp.jks";
cfg.transport.tls.truststorePassword = "changeit";

PreparedRequest request = RequestBuilder.build(cfg, xml, StandardCharsets.UTF_8, "my-app/1.0");
SoapResponse response = new SoapClient().send(request, cfg.transport);

String body = XmlCharsets.decode(response.body, response.contentType()).text;
```

`SoapClient` keeps no per-call state. The one process-wide piece is the proxy `Authenticator`, which `HttpURLConnection`
requires: it is installed once and looks credentials up by proxy host and port, so concurrent calls through different
proxies do not interfere. `SoapClient`'s static initialiser also sets two JDK system properties if they are not already
set (`sun.net.http.allowRestrictedHeaders`, so `Host` and friends can be overridden, and
`jdk.http.auth.tunneling.disabledSchemes`, so Basic proxy authentication works for HTTPS); both must be set before the
JDK's HTTP classes are first used.

## The web UI

`java -jar SoapCLI.jar serve` starts `web.WebServer` (the JDK's built-in HTTP server, so there is still one runtime
dependency). It serves two things:

- **The interface**, static files under `/web/` in the classpath. They are the compiled output of `web-ui/`, committed to
  the repository, so the Maven build needs neither Node nor a network. CI rebuilds them and fails if the result differs
  from what is committed. File names are matched against a strict pattern (no dot segments, known extensions only);
  everything under `assets/` is immutable and cached.
- **A JSON API** over the engine. The UI sends every call through it.

| Route | |
|---|---|
| `GET /healthz` | liveness (no login), used by the Docker `HEALTHCHECK` |
| `GET /api/info` | version, limits, allowed destinations, whether insecure TLS is allowed, profiles file |
| `GET /api/profiles` | profiles with secrets masked (`Profiles.describe`) |
| `POST /api/send` | build and send a request (`ApiModel.SendRequest` → `SendResponse`) |
| `POST /api/preview` | the request that would be sent, with credentials masked |
| `POST /api/attachments/check` | open an attachment path, confined to the attachments folder, and report its name and size |
| `GET /api/profiles/{name}/definition` | a profile as the file has it (not merged), secrets masked, and who extends it |
| `POST /api/profiles/save`, `/api/profiles/delete` | create, replace or remove a profile in the file |
| `GET /api/placeholders` | the options of the placeholders, with whether they can be edited |
| `POST /api/placeholders/save`, `/api/placeholders/delete` | create, replace or remove a placeholder's options |
| `POST /api/format` | pretty-print XML (same code as the CLI) |

### One engine

The page has no request builder of its own: it sends the form to `/api/send` (or `/api/preview`) and `core` does all the
work, so the UI and the command line cannot disagree. The few things the page works out itself, to show them while you type,
are small TypeScript ports in `web-ui/src/lib`: the XML scanner (`xml.ts`), the `___NAME:default___` listing
(`placeholders.ts`), the SOAP version badge (`soap.ts`) and the `cid:` references of the attachments list (`cid.ts`, which
reads only character data, never attributes, exactly like `CidReplacer`). They have tests for the same cases as their Java
counterparts; when you change one, change the other.

### Security model

A server that sends HTTP requests for whoever can reach it is an SSRF machine, so the defaults are conservative:

- Listens on `127.0.0.1` unless told otherwise; then any `Host` header other than a loopback name is refused (DNS
  rebinding). Non-loopback without a login prints a warning.
- Optional HTTP Basic login for everything except `/healthz` (constant-time comparison). Use HTTPS in front of it.
- `POST` must be `application/json`: a cross-site form or `text/plain` request is rejected with `415`, and a cross-site
  `fetch` with that type needs a CORS preflight that the server never approves.
- `HostPolicy` (`--allow-host`) is checked before every hop of a call, including redirects; sizes of request and response
  (`--max-body-mb`, `--max-response-mb`) and timeouts are capped; `--no-insecure-tls` removes the "skip checks" switch.
- Secrets never go to the browser: profiles are described masked, and a request refers to a profile by name. Attachments
  arrive as Base64, are written to a private temporary directory with server-chosen names and deleted afterwards.
- **Editing the configuration files.** `GET /api/profiles` and `/api/placeholders` say whether the file can be edited
  and why not. A change needs the server's permission (`ServeOptions.configEditAllowed`: on by default only when it listens
  on this machine, `--allow-config-edit` / `--no-config-edit` otherwise), a writable file, and the *revision* of the file the
  edit was made from (a hash of its bytes; a stale one is a `409`, so nothing is overwritten). `JsonSplice` replaces only
  the entry that changes, so comments and formatting elsewhere survive, and verifies that the result parses to the original
  document plus exactly that entry before anything is written; `ConfigFile` writes through a temporary file and an atomic
  move (in place where that is impossible, such as a single-file Docker volume), keeping the permissions and symbolic links.
  Secrets are never sent to the browser: a profile is described masked (`Profiles.own`), and a secret that comes back as
  `****` is replaced by the saved value (`Profiles.restoreSecrets`). A new or changed profile is validated like one that
  is used (`Profiles.validate`), as are the profiles that extend it. All of these are JSON `POST`s, like the rest.
- An attachment can also be named by **path**, and then the server reads that file: `ApiService.resolveAttachment`
  confines it to `--attachments-dir` (default: the working directory, `/work` in Docker). The path is made absolute and
  normalised and compared with the folder *before* the disk is touched (so a path outside it reveals nothing about what
  exists there), then resolved with `toRealPath` and compared again (so a symbolic link cannot leave the folder), and it
  must be a readable regular file. The files attached this way count against `--max-body-mb` together, and the endpoint
  that checks a path is a JSON `POST` like the rest. Profile `attachments` are the administrator's and are not confined.
- `Content-Security-Policy: default-src 'none'; script-src 'self'; ... connect-src 'self'` (the page only talks to its own
  server, which is the one that calls the services), plus `nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`
  and `Cross-Origin-Resource-Policy: same-origin`.
- The access log has method, path, status and time, never bodies or credentials.

### Docker

The `Dockerfile` is multi-stage (Maven + JDK 21 to build, a slim JRE 21 to run; the jar still targets Java 11), runs as an
unprivileged user, and uses `ENTRYPOINT java -jar SoapCLI.jar` with `CMD ["serve"]`, so the image starts the UI and any
other arguments select the command line client. `healthcheck` is a subcommand of the jar, so the image needs no `curl`.
The release workflow publishes `ghcr.io/<owner>/soapcli:<version>` and `:latest` as a manifest list for `linux/amd64` and
`linux/arm64`. The build stage is pinned to `$BUILDPLATFORM` (the jar is platform independent, so Maven runs once and
natively); only the runtime stage is built per platform, with QEMU doing the few `RUN` steps of the arm64 one. CI builds
and runs the arm64 image under emulation. To republish just the image of an existing tag, run the release workflow with
`docker_only` set (it skips the GitHub release and the jar and builds from the selected ref).

### Working on the UI

```bash
cd web-ui && npm ci
npm run dev       # Vite on :5173, proxying /api to `java -jar SoapCLI.jar serve` on :8080
npm test          # Vitest: the helpers (xml, placeholders, soap, cid, attachments, storage, server API, commands)
npm run build     # typecheck + build into ../src/main/resources/web (deterministic: commit the result)
```

Design notes: React 19 with hash routing (`#/request`, `#/library`, `#/profiles`, `#/settings`) and relative URLs, so it works from any
path prefix; `components/ui` and `icons` are the TailAdmin components the interface uses (MIT, see
`THIRD_PARTY_NOTICES.md`); the font is bundled, so nothing is fetched from a CDN. Browser storage holds drafts, saved
requests, history and settings, never secrets or attachment contents.

## Tests

`mvn test` runs a JUnit 5 suite with no network access:

- Pure unit tests for the XML helpers, placeholders, SOAP version detection, attachments, profiles and option parsing.
- Integration tests on `127.0.0.1` using the JDK's HTTP/HTTPS server (`MockServer`): headers on the wire, redirects,
  timeouts, MTOM bodies, charsets.
- Real TLS and mutual TLS with certificates generated by the JDK's `keytool` (`TestPki`), and an authenticating HTTP
  proxy that supports `CONNECT` (`TestProxy`).
- `Cli` is driven in-process, asserting on stdout, stderr and the exit code.
- `WebServer` is started on a free port and exercised over HTTP: routing, login, `Host` and content-type checks, the
  destination policy, size limits, attachments, and static file serving.

`npm test` in `web-ui` runs the Vitest suite for the browser engine. CI also builds the Docker image and checks both
modes (command line with its exit codes, and the UI with and without a login).
