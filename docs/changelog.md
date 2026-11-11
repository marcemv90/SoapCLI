# Changelog

## 1.1.0

A large clean-up release: the tool was rewritten into a tested core plus a thin command line layer, several things that
did not work were fixed, and a few behaviours changed on purpose. **Read "Behaviour changes" before upgrading scripts.**

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
