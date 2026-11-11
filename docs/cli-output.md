# Output options

## What goes where

| Stream | Content |
|--------|---------|
| **stdout** | The response body, and nothing else. (With `--dry-run`: the request preview.) |
| **stderr** | The HTTP status line, warnings, errors, and the extra information of `-i` and `--debug`. |

That separation is what makes `java -jar SoapCLI.jar ... > response.xml` and `... | xmllint --format -` behave.

## Formatting

By default the response is **pretty-printed**: elements are indented and blank lines between them are removed. Only the
whitespace *between* elements changes; text, CDATA, comments, entity references (`&amp;`), attribute quoting and
namespace prefixes are kept exactly as the server sent them, and elements with mixed content are left alone. If the body
is not well-formed XML (an HTML error page from a proxy, plain text) it is shown unchanged. A leading byte order mark
and characters XML forbids are removed from what is displayed.

!!! note "No DTD processing"
    The formatter never resolves DTDs or external entities, so a malicious or compromised server cannot make `SoapCLI`
    read local files or contact other hosts through the response.

### `--raw`

`--raw` prints the body **exactly as received** (as bytes): no decoding, no formatting, no colour. Use it when you need
the original bytes, for example to verify a signature or to diff responses. Responses that are not text (multipart/MTOM
responses and other binary types) are always printed raw.

## Colours

Colour is **automatic**: on only when you are in an interactive terminal.

| Option / environment | Effect |
|----------------------|--------|
| *(nothing)* | Colour if stdout is a terminal that supports it |
| `--color` | Always colour, even into a pipe or file |
| `--no-color` | Never colour |
| `NO_COLOR=1` | Never colour (unless `--color` is given) |
| `FORCE_COLOR=1` | Colour even when not a terminal |

Java does not offer a reliable way to ask only "is stdout a terminal?": the check also looks at stdin. As a result, colour
may not switch on automatically when you pipe the request in (`cat request.xml | java -jar ...`); add `--color` if you
want it there. On Windows, automatic colour is limited to terminals known to support it (Windows Terminal, ConEmu,
Git Bash...).

Choose the palette with `--theme` (see [Themes](cli-themes.md)).

## Saving the response: `-o`

```bash
java -jar SoapCLI.jar --endpoint https://example.com/soap --request-file request.xml -o response.xml
```

writes the (formatted, uncoloured) body to the file, in the encoding the document declares, and keeps stdout empty. Add
`--raw` to save the exact bytes.

## Status line and headers

- A status line is printed on stderr after every response: `HTTP 500 Internal Server Error (18 ms, 175 bytes)`.
  `-q` / `--quiet` hides it (warnings and errors are still shown).
- `-i` / `--include` also prints the response headers on stderr. Secret-looking headers (`Set-Cookie`,
  `Authorization`...) are masked unless you add `--show-secrets`.
- If the answer is a redirect that was not followed, the status line is followed by where it pointed and how to follow it.

## Encoding of the output

When writing to a file or pipe, `SoapCLI` always uses UTF-8. When writing to an interactive terminal it uses the
terminal's own encoding (as reported by Java).
