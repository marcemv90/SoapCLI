# Placeholders

`SoapCLI` lets you parameterize your SOAP XML using **placeholders with built-in defaults**.
This is useful when you want to:

- Reuse the same XML template across environments.
- Inject values (IDs, tags, headers, etc.) from the command line.
- Keep sensible defaults in the XML while still allowing overrides.

!!! note
    Of course this is just very basic parameterization. For more advanced use cases, you will need to use a proper scripting and pipe the resulting XML from stdin.

## Placeholder syntax

A placeholder has the following syntax and can appear **anywhere** in the XML text:

```console
___NAME:defaultValue___
```

Where:

- `NAME` is the placeholder key: it starts with a letter or `_` and then uses letters, digits, `_`, `.` or `-`.
- `defaultValue` is used when you do **not** provide an override. It may be empty (`___NAME:___`), may span several
  lines and ends at the next `___`.

Because the name is restricted, a stray `___` elsewhere in the document (a separator in some text, a Base64 blob...) is
left alone instead of being mistaken for a placeholder.

## Overriding placeholders

At runtime, you override placeholders using the repeatable `--arg` option:

```console
--arg NAME=VALUE
```

- The value is inserted **exactly as written** (`--arg` is replacing raw text, which is what allows the cases below).
- An empty value is allowed: `--arg NAME=` replaces the placeholder with nothing.
- Leading and trailing spaces in the value are kept.
- `--placeholder` is an alias of `--arg`.

When the value is text that must be valid XML content (it may contain `&`, `<`, quotes...) use `--arg-text`, which
escapes it for you:

```bash
--arg-text 'CUSTOMER=Tom & Jerry <3'      # inserted as: Tom &amp; Jerry &lt;3
```

Because replacement happens on the raw XML text **before** any attachment or MTOM processing, a placeholder can be used
almost anywhere in the XML:

- Element names.
- Attribute names and values.
- Text nodes.
- Comments.
- CDATA sections.
- Namespace prefixes and URIs.

Example XML snippet:

```xml
<___TAG:order___ id="___CID:defaultCid___">
  <!-- Request for ___TAG:order___ with CID ___CID:defaultCid___ -->
  <![CDATA[customerId=___CID:defaultCid___]]>
</___TAG:order___>
```

Run with overrides:

```bash
java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml \
  --arg TAG=invoice \
  --arg CID=abc123
```

If you omit `--arg TAG=...` or `--arg CID=...`, the defaults from the XML (`order` and `defaultCid` in the example above)
are used automatically.

## Catching typos

If an `--arg` name does not match any placeholder in the request (for example `--arg CIDD=1` instead of `CID`), a
warning is printed on stderr, since the value would otherwise be silently ignored:

```text
Warning: Argument 'CIDD' is not used by any ___CIDD:default___ placeholder in the request
```

## Dropdowns in the web UI

In the [web UI](web-ui.md#placeholder-options) a placeholder can offer a list of options, kept in a file of its own, so its
variable becomes a dropdown. The command line client does not read that file: `--arg` takes any value.

## Placeholders in profiles

A profile can set values for the placeholders with an `args` object, so each environment carries its own IDs; command
line `--arg` values override them. See [Profiles](cli-profiles.md).
