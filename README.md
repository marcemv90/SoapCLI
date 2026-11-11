# SoapCLI

<p align="center">
  <img src="img/SoapCLI-logo-01.png" alt="SoapCLI logo" width="40%" />
</p>

`SoapCLI` is a small command line tool for sending SOAP requests over HTTP/HTTPS: a quick, scriptable
alternative to a GUI client for everyday calls.

- **Simple** – one JAR (Java 11+), straightforward options, one dependency (Jackson, to read profiles).
- **Script-friendly** – the response body is the only thing on stdout, the HTTP status goes to stderr, and the exit code
  tells you what happened. Colour is only used on a terminal.
- **Flexible** – SOAP 1.1 and 1.2 (auto-detected), custom headers, Basic/Bearer auth, client certificates and custom
  trust stores, HTTP/SOCKS proxies, MTOM attachments, placeholders and per-environment profiles.

---

## Quick start

### Get it

Download `SoapCLI-<version>.jar` from the [releases page](https://github.com/marcemv90/SoapCLI/releases), or build it
(needs JDK 11+ and Maven):

```bash
mvn clean package
```

This runs the tests and produces `target/SoapCLI.jar`, which includes all dependencies.

### Run

```bash
java -jar target/SoapCLI.jar --help
```

### Example call

```bash
java -jar SoapCLI.jar --endpoint https://example.com/soap --request-file request.xml
```

```text
HTTP 200 OK (143 ms, 312 bytes)          <- stderr
<soapenv:Envelope ...>                   <- stdout: the formatted response
  ...
```

Typical variations:

```bash
# SOAP 1.1 service that wants a SOAPAction (the version is read from the Envelope namespace)
java -jar SoapCLI.jar --endpoint https://example.com/soap --request-file request.xml --soap-action urn:GetOrder

# Use a saved profile for an environment, override one value
java -jar SoapCLI.jar --profile acceptance --request-file request.xml --arg ORDER_ID=42

# See exactly what would be sent, without sending it
java -jar SoapCLI.jar --profile acceptance --request-file request.xml --dry-run
```

### Exit codes

| Code | Meaning |
|------|---------|
| 0 | the server answered with HTTP 2xx |
| 1 | bad command line, profile, file or keystore (nothing was sent) |
| 2 | the request could not be delivered (DNS, connection, timeout, TLS, proxy) |
| 3 | the server answered with another status (4xx, 5xx / SOAP fault, a redirect that was not followed) |

---

## Documentation

Full documentation (usage, options, profiles, TLS, examples, FAQ...) is available on GitHub Pages:

https://marcemv90.github.io/SoapCLI/

If you are upgrading from the first version, read the [changelog](docs/changelog.md): a few behaviours changed on
purpose (most notably the exit codes).

### Project layout

```text
src/main/java/io/github/marcemv90/soapcli/
  core/   the SOAP client: request building, HTTP transport, TLS, proxy, profiles, XML helpers.
          No console I/O and no System.exit, so it can be reused (a web UI is planned, see docs/architecture.md).
  cli/    the command line front end: option parsing, output, colours, exit codes.
src/test/java/...   JUnit 5 tests, including real TLS / mutual TLS / authenticating-proxy tests on local sockets.
docs/               MkDocs site.
```

### Building and previewing the documentation

The site is built from `docs/` with [MkDocs](https://www.mkdocs.org/) and the
[Material for MkDocs](https://squidfunk.github.io/mkdocs-material/) theme and is deployed by
`.github/workflows/gh-pages.yml`. To preview it locally with Docker:

```bash
docker build -f Dockerfile.docs -t soapcli-docs .
docker run --rm -p 8000:8000 -v "$(pwd)":/docs soapcli-docs
```

Then open http://localhost:8000/.

---

## DISCLAIMER

This project is being developed by a monkey with a shotgun (me) with the help of an AI agent. It is your responsibility
to test the code and ensure it works as expected in your environment before using it in production or critical systems.
