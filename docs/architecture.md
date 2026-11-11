# Architecture

Notes for contributors, and for the planned web UI.

## Layout

```text
io.github.marcemv90.soapcli
├── core   the engine. No System.out/err, no System.exit, no terminal knowledge.
│   ├── CallConfig, TransportOptions, TlsConfig, ProxyConfig   what the user asked for (plain fields)
│   ├── Profiles, Profile                                      named presets: extends, ${ENV}, masking
│   ├── RequestBuilder → PreparedRequest                       placeholders, SOAP version, attachments, headers
│   ├── SoapClient → SoapResponse                              HTTP(S) transport, redirects, errors
│   ├── TlsSupport, ProxySupport                               key/trust stores, --insecure, proxy auth
│   ├── MultipartBody, CidReplacer                             MTOM/XOP and attachments
│   └── XmlScanner, XmlFormat, XmlCharsets, Placeholders ...   XML helpers (no DOM, no DTDs)
└── cli    the command line front end
    ├── Main, Cli                                              wiring and the run loop (Cli returns the exit code)
    ├── Options, OptionSpec, ArgParser                         one option table drives parsing AND --help
    └── ResponsePrinter, RequestPreview, AnsiColorizer ...     presentation
```

The dependency only goes one way: `cli` uses `core`, never the opposite.

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

## Notes for the web UI

What is already in place for it:

- `CallConfig` has only public fields, so a JSON request from a browser can be mapped onto it directly.
- `Profiles` loads, resolves (`extends`, `${ENV}`) and describes profiles without any CLI involvement; `describe` returns
  a copy with secrets masked, which is what a UI should show.
- `PreparedRequest` and `SoapResponse` are plain data, ready to be serialised as the "what was sent / what came back"
  view.

What will need care when it is added:

- **A server that sends HTTP requests on behalf of its users is an SSRF machine.** It needs authentication in front of
  it (a reverse proxy with SSO is the simplest), and ideally an allow-list of target hosts.
- **Secrets stay on the server.** Profile passwords are read from the profiles file or the environment; the API must
  return masked profiles only, and requests must refer to profiles by name.
- **Concurrency.** Calls run on different threads; per-call TLS contexts are created for each call (cache them if it
  becomes a cost) and the process-wide settings above are shared.
- **Packaging.** The JDK's built-in `com.sun.net.httpserver` is enough for a small UI and keeps the "one JAR, one
  dependency" property; the Docker image would be a slim Java 11+ runtime, a non-root user, the JAR, a mounted profiles
  file and secrets passed as environment variables.
- A natural next step is to turn `core` into its own Maven module so the CLI and the web UI are two thin front ends over
  it.

## Tests

`mvn test` runs a JUnit 5 suite with no network access:

- Pure unit tests for the XML helpers, placeholders, SOAP version detection, attachments, profiles and option parsing.
- Integration tests on `127.0.0.1` using the JDK's HTTP/HTTPS server (`MockServer`): headers on the wire, redirects,
  timeouts, MTOM bodies, charsets.
- Real TLS and mutual TLS with certificates generated by the JDK's `keytool` (`TestPki`), and an authenticating HTTP
  proxy that supports `CONNECT` (`TestProxy`).
- `Cli` is driven in-process, asserting on stdout, stderr and the exit code.
