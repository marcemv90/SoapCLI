# Attachments

An attachment is a file referenced from the request as `cid:<CID>`. You tell `SoapCLI` which file each CID stands for
with the repeatable option:

```text
--attachment <CID>=<path>
```

There are two ways to deliver the files: inline Base64 (the default) or MTOM/XOP (`--mtom`).

## How `cid:` references are found

- Only **character data** is searched: element text and CDATA sections (inline mode) or element text (MTOM).
  Attributes, comments and tags are never touched, and the rest of your XML is left exactly as you wrote it.
- Matching is **exact**: `cid:message1` does not match inside `cid:message10` or `cid:message1-b`. A reference ends at
  the first character that cannot be part of a content-id (letters, digits and `. _ - ~ % @ +` can).
- You get a warning on stderr when an attachment is not referenced anywhere, or (inline mode) when its CID only appears
  in attributes, which inline mode cannot replace.

## Inline Base64 attachments

By default `SoapCLI` replaces each `cid:CID` in the text of the SOAP body with the Base64 contents of the file and sends
one normal request (no multipart).

```bash
java -jar SoapCLI.jar \
  --endpoint https://example.com/service \
  --request-file request.xml \
  --attachment message=/tmp/file.bin
```

In your SOAP XML you might have:

```xml
<payload payloadId="cid:message" contentType="text/plain">
  <value>cid:message</value>
</payload>
```

After processing, the `<value>` holds the Base64 text of `/tmp/file.bin`; the `payloadId` attribute keeps its
`cid:message`.

With `--dry-run` or `--debug` the Base64 is not dumped on screen; it is shown as `[base64 of file.bin, 1234 bytes]`.

---

## Attachments with MTOM/XOP

When the service expects MTOM (as GUI clients send it to, for example, the Domibus `wsplugin`), combine `--attachment`
with `--mtom`:

- `--mtom` – send the SOAP request as `multipart/related` with an `application/xop+xml` root part.
- `--attachment <CID>=<path>` – adds a binary part with `Content-ID: <CID>` and replaces `cid:<CID>` in the element text
  with an `xop:Include` reference.

### The CID rule

For MTOM/XOP to work with typical servers:

- Anywhere you reference the attachment in the SOAP XML, you use `cid:<CID>`, e.g. `payloadId="cid:message1"`,
  `href="cid:message1"` or `<value>cid:message1</value>`.
- The corresponding MIME part has `Content-ID: <message1>` (the angle brackets are MIME syntax; the id itself is
  `message1`). `SoapCLI` does this for you: the CID you pass to `--attachment` is the one used in the part.

References in **attributes** stay as they are, which is the "attachment by `href`" style some services use; only
references in element text become `xop:Include`.

Example:

```bash
java -jar SoapCLI.jar \
  --endpoint http://localhost:18080/domibus/services/wsplugin \
  --request-file request.xml \
  --attachment message1=/tmp/payload.xml \
  --soap-action submitMessage \
  --mtom
```

```xml
<ns:PartInfo href="cid:message1">
  <ns:PartProperties>
    <ns:Property name="MimeType">text/xml</ns:Property>
  </ns:PartProperties>
</ns:PartInfo>
...
<soap:Body>
  <eu:submitRequest>
    <payload payloadId="cid:message1" contentType="text/xml">
      <value>cid:message1</value>
    </payload>
  </eu:submitRequest>
</soap:Body>
```

With `--mtom`, `SoapCLI`:

- Builds a `multipart/related` message with:
    - Root part: `application/xop+xml; type="application/soap+xml"` (or `text/xml` for SOAP 1.1) containing the SOAP
      Envelope.
    - One binary part per attachment, with `Content-ID: <CID>` and the file streamed as-is.
- Transforms the literal `cid:message1` in element content into:

  ```xml
  <inc:Include href="cid:message1" xmlns:inc="http://www.w3.org/2004/08/xop/include"/>
  ```

!!! warning "The `action` parameter is no longer hard-coded"
    Earlier versions always put `action="submitMessage"` in the root part, which only suited Domibus. Now the action
    comes from `--soap-action`. For SOAP 1.2 it appears as `action="..."` in the `Content-Type` of the message and of
    the root part; if you send no `--soap-action`, no `action` parameter is sent. If your Domibus calls relied on the
    old behaviour, add `--soap-action submitMessage`.

If `--mtom` is used without any `--attachment`, there is nothing to send as MTOM; a warning is printed and a normal
request is made.

### Large files

MTOM attachment files are read from disk while the request is written, and bodies larger than 8 MiB are streamed to the
server instead of being buffered (unless you also use proxy credentials, which need the body to be replayable). Inline
Base64 attachments have to be held in memory, so prefer `--mtom` for big files.

## Supported combinations

| | `--attachment` only | `--attachment --mtom` |
|---|---|---|
| Request | one `text/xml` / `application/soap+xml` message | `multipart/related` |
| File data | Base64 text in place of `cid:CID` in element text/CDATA | binary MIME part + `xop:Include` |
| Attribute references (`href="cid:..."`) | left alone, with a warning if that is the only reference | left alone (this is normal) |
