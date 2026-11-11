# Docker

The image runs the [web UI](web-ui.md) by default **and** works as the command line client, so a machine with Docker but
without Java can use SoapCLI either way.

```text
docker run -p 8080:8080 IMAGE                          the web UI (default)
docker run --rm -v "$PWD:/work" IMAGE --endpoint ...   the command line client
```

The image is `ghcr.io/marcemv90/soapcli` (tags: a version such as `2.1.0`, and `latest`). To build it yourself:

```bash
docker build -t soapcli .
```

!!! note "Making the published image visible"
    The first time a release publishes the image to GitHub's container registry it is private; open the package's
    settings on GitHub and set its visibility to *public* if you want others to pull it.

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

### Docker Compose

The repository has a ready `compose.yaml`:

```bash
docker compose up -d
```

It publishes the UI on `127.0.0.1:8080`, reads `./config/profiles.json`, takes the login and the allowed destinations from
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

A slim Java 21 runtime (the jar itself targets Java 11), the jar, and nothing else; it runs as an unprivileged user. The
web UI is built into the jar, so the image has no web server or Node. `ENTRYPOINT` is `java -jar /app/SoapCLI.jar` and
`CMD` is `serve`, which is why arguments to `docker run` select the command line client. Volumes: `/config` (profiles) and
`/work` (the command line client's files).
