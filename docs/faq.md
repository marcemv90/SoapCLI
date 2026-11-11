# Frequently Asked Questions

## The server answers `415 Unsupported Media Type`, "Version mismatch" or complains about `SOAPAction`

You are probably speaking the wrong SOAP version. SOAP 1.1 uses `Content-Type: text/xml` plus a `SOAPAction` header;
SOAP 1.2 uses `application/soap+xml`. `SoapCLI` reads the version from your envelope's namespace, so check that the
`Envelope` uses the namespace of the version you mean, or force it:

```bash
java -jar SoapCLI.jar --endpoint ... --request-file request.xml --soap-version 1.1 --soap-action urn:MyAction
```

Run with `--dry-run` to see the headers. More in [SOAP versions](cli-soap-versions.md).

## `Warning: Could not detect the SOAP version`

The root element is not a recognisable `Envelope` (or its namespace is not declared on it). SOAP 1.2 is assumed. Use
`--soap-version` to choose explicitly.

## `PKIX path building failed` / "the server certificate is not trusted"

The server's certificate is signed by a CA that your Java installation does not know (an internal CA, or a self-signed
test certificate). Trust it with a truststore, or skip verification for a throw-away test:

```bash
java -jar SoapCLI.jar --endpoint https://test.example.com/soap --request-file request.xml \
  --truststore corp-truststore.jks --truststore-password changeit
# only for quick tests:
java -jar SoapCLI.jar --endpoint https://test.example.com/soap --request-file request.xml --insecure
```

See [TLS](cli-tls.md) for how to build the truststore.

## "the certificate does not match the host name"

The certificate does not list the name (or IP) you used in the URL. Use the host name the certificate was issued for. If
you must reach the server by IP, you can keep the right name with a header: `--header "Host: real.name.example.com"`
(the certificate check still looks at the name in the URL, so use `--insecure` for that test).

## The server wants a client certificate

Pass the keystore holding it: `--keystore client.p12 --keystore-password-env KS_PASSWORD`. The alias is optional if the
keystore has one private key. See [TLS](cli-tls.md).

## I am behind a corporate proxy

```bash
java -jar SoapCLI.jar --endpoint ... --request-file request.xml \
  --proxy-type http --proxy-host proxy.corp.example.com --proxy-port 8080 \
  --proxy-user alice --proxy-password-env PROXY_PASSWORD
```

Put it in a shared profile so you only do it once ([Proxy](cli-proxy.md)). `SoapCLI` does not read `HTTPS_PROXY`.
`407` means the proxy rejected the credentials, or wants a scheme other than Basic.

## Why does the exit code not match the HTTP status?

Exit codes are 8 bits wide on Linux and macOS (500 would become 244), so `SoapCLI` uses a few meaningful codes: `0` for
2xx, `1` for usage errors, `2` for network errors and `3` for any other HTTP status. The exact status is printed on
stderr. See [Return codes](cli-rc-and-errors.md).

## I get a `302` and exit code 3

The server redirected the request and `SoapCLI` does not follow redirects unless asked (re-sending a POST elsewhere
silently would be surprising). Look at the `Location` it prints, fix your endpoint, or add `--follow-redirects`.

## The call times out

The default is 30 s to connect and 120 s to wait for the answer. Raise it: `--timeout 600`. A message that says it could
not connect points at the network or the proxy instead.

## Accented characters look wrong

`SoapCLI` decodes the response using its declared charset and prints UTF-8 to files and pipes. If your terminal shows
garbage, its font or code page is not UTF-8 (on a Windows `cmd.exe`, try `chcp 65001` or use Windows Terminal). To save
the response untouched use `--raw -o response.xml`.

## The output has escape codes like `^[[38;5;81m` in a file

Colour is automatic and off when stdout is not a terminal, so this only happens with `--color` or `FORCE_COLOR`. Remove
those, or add `--no-color` (or set `NO_COLOR=1`).

## How do I keep passwords out of my shell history?

Use the `-env` variants (`--auth-basic-env`, `--keystore-password-env`, `--proxy-password-env`...) or `${VAR}`
references in a [profile](cli-profiles.md).

## How do I run the same request against several environments?

Define one [profile](cli-profiles.md) per environment (with `extends` for what they share) and select it with
`--profile`. Use [placeholders](cli-placeholders.md) for the values that change inside the request.

## Can I pipe the response into another tool?

Yes: the body is the only thing on stdout. Use `--no-color` (automatic in a pipe) and `-q` to silence the status line:

```bash
java -jar SoapCLI.jar --endpoint ... --request-file request.xml -q | xmllint --xpath '//faultstring/text()' -
```

Use `--raw` if the tool needs the exact bytes the server sent.

## `--arg` seems to be ignored

Check the warning on stderr: the name must match a `___NAME:default___` placeholder in the request exactly (names are
case-sensitive). See [Placeholders](cli-placeholders.md).

## The web UI says "The browser could not reach the service"

When the UI sends from **your browser**, the service must allow it with CORS headers, and most SOAP services do not.
Press *Check if the host answers* to tell a CORS block from an unreachable host, and *Send through the SoapCLI server* to
send the same request from the server, which is not subject to CORS. Also: a page served over HTTPS cannot call `http://`
URLs, and a self-signed certificate must be accepted in the browser first (open the URL in a tab). See
[Web UI](web-ui.md#cors-why-a-call-from-the-browser-can-fail).

## Can I use the web UI against a service that needs a client certificate or a proxy?

Yes, through the **SoapCLI server** transport: put the certificates and proxy in a [profile](cli-profiles.md) on the
machine that runs `serve` and choose it in the UI. A browser cannot be given a client certificate by a web page.

## Java is not installed on this machine

Use the [Docker image](docker.md) instead: `docker run -p 127.0.0.1:8080:8080 ghcr.io/marcemv90/soapcli` for the web UI, or
`docker run --rm -v "$PWD:/work" ghcr.io/marcemv90/soapcli --endpoint ... --request-file request.xml` for the command
line client.

## The container cannot reach `localhost:8081` on my machine

Inside a container `localhost` is the container. Use `host.docker.internal` instead (on Linux add
`--add-host=host.docker.internal:host-gateway`), or `--network host`. If you use the UI's *This browser* transport this
does not apply, because the call is made by your browser, not by the container.

## Can the web UI live under a path such as `/soapcli/`?

Yes: the interface only uses relative URLs and hash routing. Proxy `/soapcli/` to the server's `/`
([example](docker.md#behind-a-reverse-proxy)).

## How do I let a team use it without exposing my credentials?

Run one `serve` (or one container) with a login, `--allow-host` for the systems it may call, HTTPS in front of it, and the
secrets in the profiles file or the environment of the server; the browser only ever sees profiles with their secrets
masked. See [Sharing it with a team](web-ui.md#sharing-it-with-a-team).
