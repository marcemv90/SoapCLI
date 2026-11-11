# TO-DO List

!!! info "Disclaimer"
    This list is not exhaustive and is subject to change. I'm developing this tool in my free time. I'm not a professional developer, so please don't expect miracles. I'm open to contributions, but I can't promise anything.

    This project is PR welcome!

## Next big thing: a web UI in a Docker image

The plan is to be able to start `SoapCLI` as a small web application (a browser client for the same engine) and ship it
as a Docker image, to deploy it on a server and use it from the browser against the servers that server can reach, with
profiles shared by the team.

- [ ] `--serve` mode (or a separate launcher) with a minimal web server and a single-page UI
- [ ] REST API over the existing engine: send a request, list/show profiles (secrets never leave the server)
- [ ] Request editor with history, response viewer (formatted, with status and headers)
- [ ] Dockerfile + image (non-root user, profiles file as a mounted volume, secrets via environment variables)
- [ ] Safety for shared deployments: authentication in front of it, optional allow-list of target hosts (SSRF),
      audit log without secrets

The groundwork is done: the engine lives in a `core` package with no console I/O and no `System.exit`, the profiles
store can be used without the CLI, and the request that is previewed is the object that is sent. See
[Architecture](architecture.md) for the design notes.

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
