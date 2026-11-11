# Changelog

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
