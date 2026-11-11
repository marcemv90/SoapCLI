# TO-DO List

!!! info "Disclaimer"
    This list is not exhaustive and is subject to change. I'm developing this tool in my free time. I'm not a professional developer, so please don't expect miracles. I'm open to contributions, but I can't promise anything.

    This project is PR welcome!

## Web UI and Docker

Done in 2.1.0: see [Web UI](web-ui.md) and [Docker](docker.md).

- [x] `serve` mode with a web server and a single-page UI, embedded in the JAR
- [x] JSON API over the engine: send, preview, format, profiles (secrets never leave the server)
- [x] Request editor with attachments, history and a library; response viewer with status, headers and the built request
- [x] Dockerfile and image: UI by default, command line client on demand; non-root; published to `ghcr.io`
- [x] Safety for shared deployments: login, allow-list of destinations (checked on redirects), size and timeout limits,
      access log without secrets
- [ ] Multi-architecture image (`linux/arm64` next to `linux/amd64`)
- [ ] Keep attachments in the library (today they live in memory and are never stored)
- [ ] TLS and per-user logins in the server itself (today: a reverse proxy and one shared login)
- [ ] Edit server profiles from the UI
- [ ] Decode MTOM/multipart responses in the UI and offer the parts for download

## 1. Request / Transport Features

- [x] Custom HTTP headers (e.g. `--header "Name: value"`, repeatable)
- [x] HTTP Basic auth (e.g. `--auth-basic user:pass`)
- [x] HTTP Basic auth (e.g. `--auth-basic-env VAR`)
- [x] Bearer token auth (e.g. `--auth-bearer <token>`)
- [x] Option to follow redirects (e.g. `--follow-redirects`)
- [x] SOAP 1.1 / 1.2 detection, `--soap-version`, `--soap-action`
- [x] Timeouts (`--timeout`, `--connect-timeout`)
- [ ] Read `HTTPS_PROXY` / `NO_PROXY` environment variables
- [ ] NTLM / Kerberos proxy authentication

## 2. Profiles & Configuration

- [x] Profile inheritance (e.g. `"extends": "base-profile"`)
- [x] Environment variable interpolation in profiles (e.g. `"password": "${SOAPCLI_PASS}"`)
- [x] CLI to list profiles (e.g. `--list-profiles`)
- [x] CLI to list profiles (e.g. `--list-profiles-detailed`)
- [x] CLI to show a profile (e.g. `--show-profile <name>`)

## 3. Output, UX & Scripting

- [ ] Machine-readable logging mode (e.g. `--log-json` to stderr)
- [x] `--version` flag printing tool version and exiting
- [x] `--raw`, `-o`, `-i`, `-q`
- [ ] Assertions on the response for scripts (e.g. `--expect-xpath`, `--expect-status`)

## 4. Attachments / MTOM Enhancements

- [ ] Directory-based attachments (e.g. `--attachment-dir <dir>` with a filename→CID convention)
- [ ] Per-attachment Content-Type override (e.g. `--attachment cid=/tmp/file.bin:text/plain`)
- [ ] Decode MTOM/multipart responses and save their parts

## 5. Security & Keystore Handling

- [x] Keystore password from environment variable (e.g. `--keystore-password-env VAR`)
- [x] Separate truststore configuration (`--truststore`, `--truststore-type`, `--truststore-password`)
- [x] `--insecure` for test environments, with a warning
- [ ] TLS protocol configuration (e.g. `--tls-protocols TLSv1.2,TLSv1.3`)

## 6. Developer Experience & Robustness

- [x] Refactor `SoapCLI` into smaller classes (config parsing, profiles, HTTP client, XML utilities)
- [x] Improve and standardize error messages (profiles, proxy, keystore, attachments)
- [x] Automated tests and CI on Java 11 / 17 / 21, Linux and Windows

## 7. Other

- [ ] WSDL-aware mode (read WSDL, pick operation, generate skeleton request)
