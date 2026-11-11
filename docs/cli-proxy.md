# Proxy support

`SoapCLI` supports both HTTP/HTTPS and SOCKS proxies, with or without authentication.

## Proxy via CLI flags

- `--proxy-type <type>` – `http` or `socks`.
- `--proxy-host <host>` – proxy host name or IP.
- `--proxy-port <port>` – proxy port.
- `--proxy-user <user>` – proxy username (optional).
- `--proxy-password <password>` – proxy password (optional).
- `--proxy-password-env <VAR>` – read the proxy password from an environment variable.

If you configure a proxy, the host, port and type are all required, and the user and password go together.

Example: HTTP proxy

```bash
java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml \
  --proxy-type http \
  --proxy-host proxy.example.com \
  --proxy-port 3128
```

Example: HTTP proxy with authentication

```bash
export PROXY_PASSWORD='s3cret'

java -jar SoapCLI.jar \
  --endpoint https://example.com/soap \
  --request-file request.xml \
  --proxy-type http --proxy-host proxy.example.com --proxy-port 3128 \
  --proxy-user alice --proxy-password-env PROXY_PASSWORD
```

Example: SOCKS5 proxy (DNS resolved by the proxy)

```bash
java -jar SoapCLI.jar \
  --endpoint https://internal.service/soap \
  --request-file request.xml \
  --proxy-type socks \
  --proxy-host 127.0.0.1 \
  --proxy-port 1080
```

## Proxy authentication

`SoapCLI` answers the proxy's `Basic` authentication challenge with the credentials you gave, for plain `http://`
endpoints and also for `https://` ones (where the proxy is asked to open a `CONNECT` tunnel). Java itself refuses Basic
authentication on HTTPS tunnels by default; `SoapCLI` re-enables it because you supplied the credentials explicitly. The
credentials are only ever offered to a proxy, never to the destination server.

If the proxy rejects them you get a clear message:

```text
Error: The proxy requires authentication, or rejected the credentials (HTTP 407). Check --proxy-user / --proxy-password.
```

`Basic` is the proxy authentication scheme `SoapCLI` is tested with. Proxies that insist on NTLM or Kerberos
(common in some corporate networks) are not covered: ask for a Basic-capable proxy or a SOCKS one, or use an
intermediate local proxy that handles it.

## Proxy in profiles

Proxy settings can also be defined in profiles under a `proxy` object:

```json
{
  "profiles": {
    "myProfile": {
      "endpoint": "https://example.com/soap",
      "requestFile": "requests/example.xml",
      "proxy": {
        "type": "socks",
        "host": "127.0.0.1",
        "port": 1080,
        "user": "user",
        "password": "${PROXY_PASSWORD}"
      }
    }
  }
}
```

CLI flags still override profile values. Put the proxy in a shared `base` profile and have the others
[`extends`](cli-profiles.md) it, using `"proxy": null` in the ones that must go direct.

## When no proxy is configured

`SoapCLI` does not read `HTTP_PROXY`/`HTTPS_PROXY` environment variables. Without proxy settings the JVM's own defaults
apply (for instance `-Dhttps.proxyHost=... -Dhttps.proxyPort=...` given to `java`).
