# Basic usage

## Required arguments

At minimum, you must specify an endpoint and a request body:

```bash
java -jar SoapCLI.jar --endpoint https://example.com/soap --request-file request.xml
```

The endpoint must be an `http://` or `https://` URL.

## What you get back

- The **response body** is written to **stdout** (formatted, and coloured on a terminal).
- A one-line **HTTP status** goes to **stderr**, for example `HTTP 200 OK (143 ms, 312 bytes)`.
- The **exit code** tells you whether the call worked (see [Return codes](cli-rc-and-errors.md)).

So `java -jar SoapCLI.jar ... > response.xml` saves only the XML, while you still see the status on screen. Add `-q` to
hide the status line.

## The request body

The request body can be provided either from a file or from stdin.

**From a file**

```bash
java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml
```

**From stdin**

```bash
# Read the XML from a script
./my-script.sh | java -jar SoapCLI.jar --endpoint https://example.com/soap

# Or from a command
sed 's/old/new/g' request.xml | java -jar SoapCLI.jar --endpoint https://example.com/soap

# Explicitly, with the usual dash
java -jar SoapCLI.jar --endpoint https://example.com/soap --request-file - < request.xml
```

`SoapCLI` waits for the input to finish, so a slow producer is fine. If you run it in a terminal without a request file
it tells you it is reading from stdin; finish with Ctrl-D (Ctrl-Z then Enter on Windows). An empty request is an error.

!!! tip
    Piping from stdin is especially useful when you want to generate the request body dynamically, for example from the output of a previous command or script.

### Encodings

The request file is read using the encoding it declares (a byte order mark, or `encoding="..."` in the XML
declaration; UTF-8 if neither) and is sent in that same encoding, so an `ISO-8859-1` request stays `ISO-8859-1`.
A leading BOM, which some Windows editors add, is dropped. The response is decoded the same way, using the `charset`
of its `Content-Type` first.

## SOAP version and SOAPAction

`SoapCLI` reads the SOAP version from the `Envelope` namespace and sends the matching `Content-Type`. SOAP 1.1 services
usually also want a `SOAPAction`:

```bash
java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml \
  --soap-action urn:myAction
```

See [SOAP versions](cli-soap-versions.md) for what exactly is sent in each case.

## Custom HTTP headers

Some SOAP endpoints require additional HTTP headers (correlation IDs, API keys, custom authentication schemes...). Add
arbitrary headers using the repeatable `--header` option:

- `--header "Name: value"` – adds an HTTP request header with the given name and value.

Headers are applied after the defaults, so they can override them (for example `Content-Type`, `Accept`, `User-Agent`).
Header names are case-insensitive. Restricted headers such as `Host` can be set too, which is handy to reach a virtual
host by IP address.

```bash
java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml \
  --header "X-Correlation-ID: 12345" \
  --header "X-Env: acceptance"
```

If the same header is also set by a profile, the command line wins.

!!! tip "Quoting on Windows"
    A header value with double quotes, such as `SOAPAction: "urn:myAction"`, needs different quoting in `cmd.exe`,
    PowerShell and bash. For the common case use `--soap-action urn:myAction`, which needs no quotes.

## Timeouts

- `--connect-timeout <seconds>` – time allowed to establish the connection (default 30).
- `--timeout <seconds>` – time to wait for the server's answer (default 120; `0` waits forever).

SOAP operations that build reports or process batches can be slow; raise `--timeout` for them. When a timeout expires
the error message tells you which one it was.

## Redirects

Redirects are **not** followed by default: silently re-sending a POST somewhere else is rarely what you want, so a
`301/302/307/308` answer is reported (status line, the `Location`, and exit code 3). Add `--follow-redirects` to follow
up to 5 of them by re-sending the same POST. `Authorization` and `Cookie` headers are dropped if the redirect goes to a
different host, port or scheme.

## Getting help and the version

```bash
java -jar SoapCLI.jar --help
java -jar SoapCLI.jar --version
```
