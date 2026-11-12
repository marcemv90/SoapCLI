# Debug and dry-run

`SoapCLI` provides two flags that help you understand exactly what is being sent and to troubleshoot problems:

- `--dry-run` – show what would be sent **without** performing the HTTP call.
- `--debug` – send the request **and** print extra diagnostics to stderr.

Both show the **real** request: the preview is built from the same object that is sent, so what you see is what goes out.

## Dry-run mode (`--dry-run`)

Use `--dry-run` when you want to verify the effective configuration and SOAP body but do not want to touch the remote
service.

```bash
java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml \
  --auth-basic alice:s3cret \
  --dry-run
```

```text
==== SOAP-CLI DRY-RUN ====
Endpoint: https://example.com/soap
SOAP version: 1.1

HTTP headers:
Content-Type: text/xml; charset=utf-8
Accept: text/xml, application/soap+xml, */*;q=0.9
SOAPAction: ""
User-Agent: SoapCLI/2.2.0
Authorization: Basic ****
Content-Length: 140

Request body:
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/">
  <soapenv:Body>
    <ping>hi</ping>
  </soapenv:Body>
</soapenv:Envelope>
==== END SOAP-CLI DRY-RUN ====
```

Dry-run mode prints (on stdout):

- Endpoint, the SOAP version that will be used, and proxy and TLS settings when configured.
- All HTTP request headers, including those added by profiles, `--header`, auth options and the SOAP version rules.
- The `Content-Length` of the body.
- The final SOAP XML after profiles and `--arg` placeholders are applied. With attachments, inline Base64 data is shown
  as `[base64 of file.bin, 1234 bytes]` and, with `--mtom`, the `xop:Include` form of the root part is shown, along with
  the list of attachments.

Then the program exits with code 0 **without sending** the HTTP request. The XML is pretty-printed and coloured following
the usual [colour rules](cli-output.md#colours).

## Debug mode (`--debug`)

Use `--debug` for troubleshooting while still sending the request.

```bash
java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml \
  --debug
```

When `--debug` is enabled, `SoapCLI`:

- Prints the same request preview as `--dry-run`, on **stderr**.
- Still performs the HTTP call and prints the normal response body to stdout.
- Prints the HTTP status line and the **response headers** (like `-i`).
- Shows stack traces for errors (with the underlying cause).

All debug information is written to **stderr**, so your normal stdout output (e.g. SOAP response XML) remains suitable
for piping or capturing in scripts.

## Secrets are masked

`Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie` and any header whose name suggests a credential (`token`,
`secret`, `password`, `api-key`...) are shown as `****` (`Authorization: Basic ****` keeps the scheme). The proxy password
is masked too. This means a dry-run can be pasted into a ticket without leaking the credentials.

Use `--show-secrets` if you really need to see the values.

!!! warning
    The request **body** is always shown. If it contains credentials (a WS-Security `UsernameToken`, for example) they are
    visible in the preview.

## Combining with other options

Both `--dry-run` and `--debug` take into account the same configuration sources as a normal run:

- Profiles (`--profile`, `--profile-file`).
- Placeholders and `--arg` overrides.
- Attachments via `--attachment` and `--mtom`.
- Custom headers via `--header`, SOAP version and action.
- HTTP Basic/Bearer authentication.
- TLS and proxy settings.

This makes them ideal for verifying complex requests before running them in a sensitive environment.
