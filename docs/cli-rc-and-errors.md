# Return codes and errors

`SoapCLI` is designed to be script/CI friendly. The exit code tells you what happened, and the HTTP status is printed
on stderr, so a script can tell "the server said no" from "I could not even reach it".

## Exit codes

| Code | Meaning | Examples |
|------|---------|----------|
| **0** | The server answered with HTTP **2xx**. | `200 OK`, `202 Accepted` (one-way operations answer with an empty body) |
| **1** | **Nothing was sent**: bad command line, profile, file or keystore. | unknown option, missing `--endpoint`, unreadable request file, wrong keystore password, unset `${VAR}` in a profile |
| **2** | The request **could not be delivered**, or no answer came back. | unknown host, connection refused, timeout, TLS handshake failure, proxy refusal |
| **3** | The server **answered, but not with 2xx**. | `401`, `404`, `500` (including SOAP faults), a redirect that was not followed |

When the server answers with an error status that carries a body (a SOAP fault, typically with HTTP 500), the body is
still printed on stdout, without a Java stack trace, and the exit code is 3.

!!! warning "Changed in 2.0"
    Earlier versions exited with the HTTP status itself. Exit codes are 8 bits wide on Linux and macOS, so a `500`
    became `244`, a `404` became `148`, and the documented `-ge 400` test could never succeed. Scripts that compared the
    exit code with HTTP statuses must now test for `3` and read the status from stderr (see below).

## The HTTP status

After every response a line like this is written to **stderr**:

```text
HTTP 500 Internal Server Error (18 ms, 175 bytes)
```

Hide it with `-q`. To get the numeric status in a script, redirect stderr to a file:

```bash
java -jar SoapCLI.jar --endpoint https://example.com/soap --request-file request.xml \
  > response.xml 2> status.txt
rc=$?
http_status=$(sed -n 's/^HTTP \([0-9]*\).*/\1/p' status.txt)
```

## Using exit codes from scripts

### Bash example

```bash
#!/usr/bin/env bash

java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml \
  > response.xml

status=$?

case "$status" in
  0) echo "Request succeeded" ;;
  1) echo "SoapCLI could not run the request: check the options" >&2; exit 1 ;;
  2) echo "Could not reach the server (see the error above)" >&2; exit 2 ;;
  3) echo "The server answered with an error status; the body is in response.xml" >&2; exit 3 ;;
esac
```

This pattern lets you:

- Treat 2xx as success.
- Tell an HTTP error (the server answered) from a local or network problem.
- Keep the response body for inspection even on HTTP errors.

### Using `set -e` carefully

With `set -e` a non-zero exit code stops the script immediately, *before* the line that reads `$?`. Capture the status
in the same command instead:

```bash
set -euo pipefail
status=0
java -jar SoapCLI.jar --endpoint ... --request-file request.xml > response.xml || status=$?
if [ "$status" -ne 0 ]; then
  echo "SoapCLI failed with exit code $status" >&2
fi
```

## Error messages

Errors are one line on stderr, prefixed with `Error:`, and say what to do:

```text
Error: Could not connect to 127.0.0.1:1: Connection refused
Error: Unknown host 'wrong.example.com' (DNS lookup failed)
Error: The server did not answer within 120 s (see --timeout)
Error: TLS handshake with test.example.com:8443 failed: the server certificate is not trusted. Trust its CA with --truststore <file>, or use --insecure for a throw-away test. (...)
Error: Missing endpoint: use --endpoint <URL> or a profile that defines one
```

Errors from the command line add `Run with --help to see the available options.`

No Java stack trace is printed unless you add `--debug`. If you ever see `Unexpected error:`, that is a bug worth
reporting; `--debug` gives the stack trace to include.

## Warnings

Things that probably are not what you meant are reported on stderr as `Warning: ...` and do not change the exit code:
an `--arg` that matches no placeholder, an attachment that is not referenced, a SOAP version that could not be detected,
unknown keys in a profile, and `--insecure`.
