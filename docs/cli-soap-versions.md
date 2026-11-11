# SOAP versions and SOAPAction

SOAP 1.1 and SOAP 1.2 look almost identical in XML but differ on the wire. Sending the wrong one is the classic cause of
`415 Unsupported Media Type`, "Version mismatch" or an empty `SOAPAction` error. `SoapCLI` handles both.

## How the version is chosen

1. `--soap-version 1.1` or `--soap-version 1.2`, if given (or `soapVersion` in a profile).
2. Otherwise it is **detected** from the namespace of the root `Envelope` element of your request:

   | Envelope namespace | Version |
   |--------------------|---------|
   | `http://schemas.xmlsoap.org/soap/envelope/` | SOAP 1.1 |
   | `http://www.w3.org/2003/05/soap-envelope` | SOAP 1.2 |

3. If the root is not a recognisable SOAP envelope, SOAP 1.2 is assumed and a warning is printed on stderr.

`--soap-version auto` restores detection (useful to override a version set by a profile).

## What goes on the wire

| | SOAP 1.1 | SOAP 1.2 |
|---|---|---|
| `Content-Type` | `text/xml; charset=utf-8` | `application/soap+xml; charset=utf-8` |
| Action | `SOAPAction: "<action>"` header, always present (`""` when you gave none) | `; action="<action>"` parameter of the `Content-Type`, only when you gave one |

Give the action with `--soap-action`:

```bash
# SOAP 1.1 envelope -> text/xml + SOAPAction: "urn:GetOrder"
java -jar SoapCLI.jar --endpoint https://example.com/soap --request-file get-order.xml --soap-action urn:GetOrder
```

The value may include its own surrounding quotes (`--soap-action '"urn:GetOrder"'`); they are normalised.

You can see the result without sending anything:

```bash
java -jar SoapCLI.jar --endpoint https://example.com/soap --request-file get-order.xml --soap-action urn:GetOrder --dry-run
```

```text
==== SOAP-CLI DRY-RUN ====
Endpoint: https://example.com/soap
SOAP version: 1.1

HTTP headers:
Content-Type: text/xml; charset=utf-8
Accept: text/xml, application/soap+xml, */*;q=0.9
SOAPAction: "urn:GetOrder"
User-Agent: SoapCLI/1.1.0
Content-Length: 412
...
```

## Overriding anything

Whatever the rules above produce, `--header` has the last word. If a server needs something unusual:

```bash
java -jar SoapCLI.jar --endpoint ... --request-file request.xml \
  --header "Content-Type: application/soap+xml; charset=utf-8; action=\"urn:x\"" \
  --header "SOAPAction: urn:x"
```

## MTOM

With `--mtom` the same version rules apply to the multipart message: the root part is
`application/xop+xml; type="text/xml"` (1.1) or `type="application/soap+xml"` (1.2), and for SOAP 1.2 `--soap-action`
becomes the `action=` parameter of both the outer `Content-Type` and the root part. See
[Attachments](cli-attachments.md).
