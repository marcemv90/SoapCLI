# Web UI

SoapCLI includes a browser client for the same engine as the command line: write or paste a request, press **Send**,
read the answer. It is meant to replace a heavy GUI client for everyday calls, and to be easy to share with a team.

![The web UI: a request on the left, the formatted answer on the right](assets/img/web-ui/request-light.png)

- Built with [TailAdmin](https://tailadmin.com) (React, Tailwind CSS); light and dark themes; works on a phone.
- **Everything is inside the jar.** No CDN, no font download, no extra install, and it works with no internet access.
- Every call is made **by the SoapCLI server** that serves the page, so it reaches whatever the machine running it can
  reach (`localhost`, the company network, a VPN) and can use that machine's client certificates, trust stores, proxies and
  [profiles](cli-profiles.md). Nothing depends on the service allowing cross-origin (CORS) calls.

## Starting it

```bash
java -jar SoapCLI.jar serve
```

Open <http://127.0.0.1:8080>. By default it listens on this machine only. See [Docker](docker.md) to run it as a
container, and [Sharing it with a team](#sharing-it-with-a-team) before exposing it on a network.

```bash
java -jar SoapCLI.jar serve --port 9000
java -jar SoapCLI.jar serve --help          # every option
```

## Using it

**Endpoint bar.** Pick the SOAP version (auto-detect reads the namespace of your `Envelope`), type the URL, optionally the
**SOAP action**, and press **Send** or <kbd>Ctrl</kbd>+<kbd>Enter</kbd>. SoapCLI puts the action where each version
expects it: the `SOAPAction` header for SOAP 1.1, the `action` parameter of the `Content-Type` for 1.2
([details](cli-soap-versions.md)). On the second row: the **server profile** to use, if the server has any (*View profile*
opens its configuration), the **layout** of the page, **New** to start a blank request, **Save** to keep it in the Library,
and **Copy as…** to generate commands (see below).

**Layout.** The request and the response can sit **side by side** (the default, on screens wide enough) or **stacked**,
with the response under the request. Choose with the two-pane buttons next to *New*, or in *Settings*; the choice is
remembered.

![Stacked layout: the request on top, the response below](assets/img/web-ui/request-stacked-light.png)

**Request tabs**

- **Body**: an XML editor with highlighting, folding and line numbers (*Format* pretty-prints it). Under the editor, the
  two things the body refers to are listed, so you never leave the screen:
    - **Variables**: every `___NAME:default___` in the request gets a field, so you can change a value without editing
      the XML ([placeholders](cli-placeholders.md)). A field left empty uses the default written in the request.
    - **Attachments**: every `cid:NAME` written as the **text** of an element is detected automatically, and you choose
      the file for it (button or drag and drop onto its row). A `cid:` inside an **attribute** is not an attachment and is
      not listed. In
      ```xml
      <payload payloadId="cid:message" contentType="text/xml">
         <value>cid:1234</value>
      </payload>
      ```
      only `1234` appears, because `message` is the value of an attribute. By default the file is inlined as Base64 where
      the `cid:` is written; turn on **MTOM** to send it as a binary MIME part ([details](cli-attachments.md)). A file whose
      `cid:` you delete from the body is kept in memory (retyping the reference brings it back) but is **not sent**.
      Files live in memory only, so they are not kept when you reload or open a saved request. The request, with its
      attachments in Base64, must fit the server's limit, 25 MB by default (`--max-body-mb`).
- **Headers**: add any header; disabled rows are kept but not sent.
- **Auth**: none, Basic or Bearer. Credentials are never stored (see [Your data](#your-data)).
- **Options**: timeout, follow redirects, skip TLS checks (unless the administrator disabled it).

**Response panel.** The status badge (green 2xx, amber 3xx, red 4xx/5xx; a SOAP fault is normally a red 500 with the
fault in the body), the time and the size; tabs for the formatted **Body**, the **Headers**, the **Raw** text exactly as
decoded, and the **Request** that was built, with credentials masked, a list of warnings (a variable nothing uses, an
attachment nothing references) and the XML as it was sent. Press *Show the request* to have the server build it before you
send.

**Copy as…** turns what is on screen into the equivalent `java -jar SoapCLI.jar …` command, a Docker command, or a `curl`
command (credentials become environment variables; the server builds the request for it, so headers that come from a
profile appear as `****` for you to fill in), and *Download request.xml* saves the body, so a call you tried here is easy
to repeat in a script.

**Library** keeps your *Saved* requests and the last 50 calls (*History*), searchable by name or URL, with *Export* and
*Import* of the saved ones as a JSON file. **Profiles** shows the server's profiles (below). **Settings** holds the theme,
the layout, the defaults for new requests and the details of the server (version, allowed destinations, limits).

## Server profiles

If the SoapCLI server has a [profiles file](cli-profiles.md), pick a profile next to the endpoint: it supplies the
endpoint, headers, authentication, certificates, proxy and so on, and anything you fill in the form overrides it.

![The Profiles page: the configuration of a profile as the server resolves it](assets/img/web-ui/profiles-light.png)

The **Profiles** page (in the menu, or *View profile* on the request page) shows each profile's configuration as the
server resolves it: the values after `extends`, in the order of the [documentation](cli-profiles.md#fields), with the
nested groups (`headers`, `args`, `keystore`, `proxy`...) as tables, and the same data as JSON. A profile that cannot be
resolved (for instance, one that extends a profile that does not exist) is marked with its error. From there, *Use in the
request* selects it, and *Load its request file* fills the editor with the profile's `requestFile`.

Secrets stay on the server: the browser only ever receives the profile with them masked (`****`), and `${VAR}`
references are shown as written and expanded by the server from its own environment. The server reads the file on every
request, so edits take effect immediately (press *Reload* to see them). Profiles cannot be edited from the UI.

## Your data

Saved requests, the history, the draft you are working on and your settings live in **your browser's local storage**,
never on the server. **Passwords, tokens and attachment contents are never stored**: they are forgotten when you close
the tab. *Settings → Your data* can clear the history or delete everything.

## Sharing it with a team

A SoapCLI server makes HTTP requests on behalf of whoever can reach it, and can hold credentials in its profiles. Treat it
like any internal tool that can call other systems:

- **Set a login**: `--ui-user` with `--ui-password` or `--ui-password-env` (or `SOAPCLI_UI_USER` / `SOAPCLI_UI_PASSWORD`).
  It is HTTP Basic, so put the server behind **HTTPS** (a reverse proxy; see [Docker](docker.md#behind-a-reverse-proxy)).
- **Restrict where it can call**: `--allow-host '*.corp.example.com' --allow-host 10.1.*`. Redirects are checked too, so
  an allowed host cannot bounce a request to a forbidden one.
- **Remove the dangerous switch** if you want: `--no-insecure-tls` hides "Skip TLS certificate checks".
- It limits request and response sizes (`--max-body-mb`, `--max-response-mb`) and caps timeouts (10 minutes).
- Listening on a non-loopback address without a login prints a warning at startup.

What the server does for you without being asked: it refuses cross-site requests (the API only accepts
`application/json`, which a browser cannot send cross-site without a CORS preflight that the server never approves),
checks the `Host` header when it listens on `localhost` only (a defence against DNS rebinding), and sends a strict
Content-Security-Policy: scripts may only come from the server itself.

| Option | Environment variable | Default |
|---|---|---|
| `--host` | `SOAPCLI_HOST` | `127.0.0.1` |
| `--port` | `SOAPCLI_PORT` | `8080` |
| `--profile-file` | `SOAPCLI_PROFILE_FILE` | `~/.config/soap-cli-profiles.json` |
| `--ui-user`, `--ui-password`, `--ui-password-env` | `SOAPCLI_UI_USER`, `SOAPCLI_UI_PASSWORD` | no login |
| `--allow-host` (repeatable) | `SOAPCLI_ALLOWED_HOSTS` (comma separated) | any host |
| `--no-insecure-tls` | `SOAPCLI_NO_INSECURE_TLS=true` | allowed |
| `--max-body-mb` | `SOAPCLI_MAX_BODY_MB` | 25 |
| `--max-response-mb` | `SOAPCLI_MAX_RESPONSE_MB` | 20 |

Command line options override the environment. `java -jar SoapCLI.jar healthcheck` exits 0 if a UI answers on this
machine's port (the Docker image uses it).

## Without a SoapCLI server

The interface is a set of static files (`web/` inside the jar), but everything it does goes through the server's API: a copy
served from anywhere else shows a notice ("No SoapCLI server behind this page") and cannot send. Start `serve` and open
the address it prints.

## Keyboard

| Keys | Action |
|---|---|
| <kbd>Ctrl</kbd>/<kbd>⌘</kbd> + <kbd>Enter</kbd> | Send |
| <kbd>Esc</kbd> | Close the menu on a small screen |

## Troubleshooting

| Symptom | Cause |
|---|---|
| "No SoapCLI server behind this page" | The page was not served by `serve`: open the address `serve` prints (or the container's port). |
| "Cannot reach the SoapCLI server" | The server stopped or the connection was lost. *Check the server again* once it is back. |
| "The host … is not allowed by this server's policy" | The administrator restricted the destinations (`--allow-host`). |
| "larger than 25 MB" | Raise `--max-body-mb` (the request, attachments included, is limited). |
| An attachment you expected is not listed | Its `cid:` must be the text of an element, not the value of an attribute. |
| An attachment is listed as "no file chosen" | Choose a file for it; until then `cid:NAME` is sent as written. |
