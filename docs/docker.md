# Docker

The image runs the [web UI](web-ui.md) by default **and** works as the command line client, so a machine with Docker but
without Java can use SoapCLI either way.

```text
docker run -p 8080:8080 IMAGE                          the web UI (default)
docker run --rm -v "$PWD:/work" IMAGE --endpoint ...   the command line client
```

The image is `ghcr.io/marcemv90/soapcli` (tags: a version such as `2.1.0`, and `latest`). It is published for **`linux/amd64`**
(Intel and AMD PCs and servers, Windows with Docker Desktop) and **`linux/arm64`** (Macs with Apple Silicon, ARM servers):
Docker pulls the one that matches your machine, so the same command works everywhere. To build it yourself:

```bash
docker build -t soapcli .
```

This builds for the machine you run it on. To build both platforms, use Buildx (`docker buildx build --platform linux/amd64,linux/arm64 .`).

!!! note "Publishing your own copy"
    The package is public because it is linked to a public repository. If you fork the project and publish the image
    from your fork, check on GitHub (your profile, *Packages*, `soapcli`, *Package settings*) that its visibility is what
    you want and that your repository appears under *Manage Actions access* with the **Write** role (or higher);
    without it the push is refused with `denied`.

## The web UI (default)

```bash
docker run -d --name soapcli -p 127.0.0.1:8080:8080 ghcr.io/marcemv90/soapcli
```

Open <http://localhost:8080>. `-p 127.0.0.1:8080:8080` publishes it on this machine only; `-p 8080:8080` publishes it on
the network, so **set a login first**:

```bash
docker run -d --name soapcli -p 8080:8080 \
  -e SOAPCLI_UI_USER=alice -e SOAPCLI_UI_PASSWORD='choose-a-strong-one' \
  -e SOAPCLI_ALLOWED_HOSTS='*.corp.example.com' \
  -v "$PWD/config:/config:ro" \
  ghcr.io/marcemv90/soapcli
```

Options after the image name go to `serve`: `docker run … ghcr.io/marcemv90/soapcli serve --port 9000 --no-insecure-tls`.
The environment variables are listed in [Web UI](web-ui.md#sharing-it-with-a-team).

### Attachments by path

The web UI can attach files named by path (the path is saved with the request; see
[Attachments by path](web-ui.md#attachments-by-path)). Inside the container it reads them only from `/work` (the image
sets `SOAPCLI_ATTACHMENTS_DIR=/work`), so mount the folder with your files there, read-only:

```bash
docker run -d -p 127.0.0.1:8080:8080 -v "$PWD/attachments:/work:ro" ghcr.io/marcemv90/soapcli
```

In the UI, a path such as `invoice.pdf` or `/work/invoices/invoice.pdf` then refers to `attachments/invoice.pdf` or
`attachments/invoices/invoice.pdf` on the host. Nothing outside `/work` can be attached. (`compose.yaml` has the same
mount, commented out.)

### Profiles and secrets

Mount a profiles file at `/config/profiles.json` (the image sets `SOAPCLI_PROFILE_FILE` to it) and pass the secrets it
refers to as environment variables:

```json
{ "profiles": { "acceptance": {
    "endpoint": "https://acc.corp.example.com/soap",
    "authBasic": "${ACC_CREDENTIALS}",
    "truststore": { "path": "/config/corp.jks", "password": "${CORP_TS_PASSWORD}" }
} } }
```

```bash
docker run -d -p 127.0.0.1:8080:8080 -v "$PWD/config:/config:ro" \
  -e ACC_CREDENTIALS='svc:password' -e CORP_TS_PASSWORD=changeit ghcr.io/marcemv90/soapcli
```

Paths in a profile are paths **inside the container**: put the keystore and truststore next to `profiles.json` in
`/config`. The container runs as an unprivileged user (uid 10001), so the files must be readable by others
(`chmod a+r`), or run it with `--user "$(id -u):$(id -g)"`.

The web UI can also **edit** the profiles file, and the file of [placeholder options](web-ui.md#placeholder-options)
(`/config/placeholders.json`; the image sets `SOAPCLI_PLACEHOLDERS_FILE`). Inside a container the server listens on a
network address, so editing is off by default; to turn it on, mount `/config` without `:ro` and set
`SOAPCLI_ALLOW_CONFIG_EDIT=true` (and a login):

```bash
docker run -d -p 127.0.0.1:8080:8080 -v "$PWD/config:/config" -e SOAPCLI_ALLOW_CONFIG_EDIT=true \
  -e SOAPCLI_UI_USER=alice -e SOAPCLI_UI_PASSWORD='choose-a-strong-one' ghcr.io/marcemv90/soapcli
```

The container user must be able to write to that folder (`chown 10001 config`, or `--user "$(id -u):$(id -g)"`).

### Docker Compose

The repository has a ready `compose.yaml`:

```bash
docker compose up -d
```

It publishes the UI on `127.0.0.1:8080`, reads `./config/profiles.json` and `./config/placeholders.json`, takes the login and the allowed destinations from
a `.env` file, and hardens the container (read-only file system, no capabilities, no new privileges).

### Behind a reverse proxy

The UI speaks plain HTTP and the login is HTTP Basic, so a team deployment should sit behind a proxy that terminates HTTPS:

```nginx
server {
    listen 443 ssl;
    server_name soapcli.corp.example.com;
    ssl_certificate     /etc/ssl/soapcli.crt;
    ssl_certificate_key /etc/ssl/soapcli.key;

    client_max_body_size 30m;            # a little more than --max-body-mb (default 25)
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_read_timeout 700s;         # SoapCLI waits up to 10 minutes for a slow SOAP answer
    }
}
```

The interface uses relative URLs, so it also works under a path prefix (`location /soapcli/ { proxy_pass http://127.0.0.1:8080/; }`).
If you run the server on the host with `--host 127.0.0.1` (rather than in the container) and the proxy forwards another
`Host`, set `proxy_set_header Host 127.0.0.1;`: on a loopback-only server any other `Host` is refused.

### Health and logs

The image has a `HEALTHCHECK` (`docker ps` shows *healthy*). `docker logs soapcli` shows one line per request (method,
path, status, time), never request bodies or credentials.

## The command line client

Any argument other than `serve` runs the command line client, with the same options as `java -jar SoapCLI.jar`
([Basic usage](cli-basic-usage.md)). The working directory is `/work`, so mount your files there:

```bash
# a request file from the current directory
docker run --rm -v "$PWD:/work" ghcr.io/marcemv90/soapcli \
  --endpoint https://example.com/soap --request-file request.xml

# the request from standard input (note -i)
cat request.xml | docker run --rm -i ghcr.io/marcemv90/soapcli --endpoint https://example.com/soap

# a profile, with its secret from the host environment (-e VAR without a value passes the host's)
docker run --rm -v "$PWD:/work" -v "$HOME/.config/soap-cli-profiles.json:/config/profiles.json:ro" \
  -e ACC_CREDENTIALS ghcr.io/marcemv90/soapcli --profile acceptance --request-file request.xml

# save the response (write as yourself, not as the image's user)
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/work" ghcr.io/marcemv90/soapcli \
  --endpoint https://example.com/soap --request-file request.xml -o response.xml
```

The exit codes are the ones described in [Return codes](cli-rc-and-errors.md): `docker run` passes them through, so scripts
work unchanged. Add `-t` (with `-i`) for colour on a terminal, or `--color`.

A shell alias makes it feel native:

```bash
alias soapcli='docker run --rm -i -v "$PWD:/work" -e SOAP_CREDENTIALS ghcr.io/marcemv90/soapcli'
soapcli --endpoint https://example.com/soap --request-file request.xml --auth-basic-env SOAP_CREDENTIALS
```

In PowerShell use `${PWD}` instead of `$PWD`.

!!! warning "`localhost` inside a container is the container"
    To call a service on the host machine, use `host.docker.internal` as the host name (Docker Desktop; on Linux add
    `--add-host=host.docker.internal:host-gateway`), or run with `--network host` on Linux.

You can also say `cli` explicitly (`docker run --rm IMAGE cli --version`).

## Corporate networks

- **A private CA** for the servers you call: add it to a truststore and use `truststore` in a profile or `--truststore`, or
  point the JVM at one with `-e JAVA_TOOL_OPTIONS='-Djavax.net.ssl.trustStore=/config/cacerts -Djavax.net.ssl.trustStorePassword=changeit'`.
- **A proxy**: use a profile with a `proxy` section, or `--proxy-*` options ([Proxy](cli-proxy.md)).
- **Pulling the image** may need your registry mirror: pull it once and `docker save` / `docker load`, or build it from source.

## What is in the image

A slim Java 21 runtime (the jar itself targets Java 11) for `amd64` and `arm64`, the jar, and nothing else; it runs as an
unprivileged user. The
web UI is built into the jar, so the image has no web server or Node. `ENTRYPOINT` is `java -jar /app/SoapCLI.jar` and
`CMD` is `serve`, which is why arguments to `docker run` select the command line client. Volumes: `/config` (profiles and placeholder options) and
`/work` (the command line client's files, and the only place the web UI attaches files from by path).
