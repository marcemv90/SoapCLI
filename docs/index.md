# SoapCLI

`SoapCLI` is a small command line tool for sending SOAP requests over HTTP/HTTPS. It is meant for the everyday case
where opening a full GUI client is overkill: edit an XML file, run one command, read the answer.

It is designed to be:

- **Simple** – a single JAR and straightforward options.
- **Script-friendly** – the response body is the only thing on stdout, the HTTP status goes to stderr, exit codes mean
  something, and colour is only used on a terminal.
- **Flexible** – SOAP 1.1 and 1.2, HTTPS with client certificates and custom trust, proxies, MTOM attachments and
  per-environment profiles.

---

!!! info "Disclaimer"
    This project is being developed by a monkey with a shotgun (me) with the help of an AI agent. It is your responsibility to test the code and ensure it works as expected in your environment before using it in production or critical systems.

---

## Requirements

- Java **11** or later to run the JAR (a JRE is enough).
- A JDK 11+ and Maven (or Docker) only if you want to build it from source.

---

## Main features

- Send SOAP requests to an HTTP/HTTPS endpoint, with or without attachments.
- **SOAP 1.1 and 1.2**: the version is detected from the envelope and the right `Content-Type` / `SOAPAction` is sent
  (see [SOAP versions](cli-soap-versions.md)).
- Request body from a file or piped from stdin; placeholders with defaults to reuse one template.
- Custom HTTP headers, HTTP Basic and Bearer authentication.
- HTTPS with client certificates (mutual TLS), custom trust stores, and an explicit `--insecure` switch for throw-away
  tests.
- HTTP/HTTPS and SOCKS proxy support, including proxy authentication.
- Inline Base64 or MTOM/XOP attachments.
- Pretty-printed, optionally coloured XML output; `--raw` and `-o` when you need the exact bytes.
- Exit codes that tell scripts what happened, and an HTTP status line on stderr.
- Named profiles for environments (`acceptance`, `production`...), with inheritance (`extends`) and environment
  variable references so secrets need not live in the file.
- `--dry-run` and `--debug` that show the real request with credentials masked.

## Where to start

- [Installation](installation-from-source.md)
- [Basic usage](cli-basic-usage.md)
- [SOAP versions and SOAPAction](cli-soap-versions.md)
- [Profiles](cli-profiles.md) – save the settings of each environment once
- [Return codes and errors](cli-rc-and-errors.md) – for scripts and CI
- [FAQ](faq.md) – common problems (self-signed certificates, proxies, `415` errors...)
- [Changelog](changelog.md) – what changed from the first version
