# Profiles

Profiles let you define reusable configuration presets for different environments
(such as `acceptance`, `staging`, `production`). A profile can define the endpoint, the request file, headers,
authentication, placeholder values, TLS and proxy settings, timeouts and more: everything you would otherwise repeat on
every command line.

A profile may also **inherit** from another one (`extends`) and refer to **environment variables** (`${VAR}`), so
secrets do not have to be stored in the file.

The [web UI](web-ui.md#server-profiles) shows the profiles and, where the server allows it, can create, edit and delete
them without disturbing the comments and formatting of the rest of the file.

!!! note
    Any CLI flag that has a corresponding field in the profiles JSON can be configured per profile.
    CLI flags still override the profile values at runtime.

If a profile contains a key with an empty value (`""`), it is ignored and treated as if it was not present. (The one
exception is a value in `args`, where an empty string is a meaningful override.)

## Default profiles file location

`SoapCLI` looks for the profiles file in this order:

1. The `--profile-file <path>` argument.
2. The `SOAPCLI_PROFILE_FILE` environment variable.
3. `~/.config/soap-cli-profiles.json`, resolved with the current user's home directory (`user.home`):
    - **Linux/macOS** – e.g. `/home/user/.config/soap-cli-profiles.json`.
    - **Windows** – e.g. `C:\Users\user\.config\soap-cli-profiles.json`.

## Workspaces

A [workspace](workspaces.md) is a folder with its own `profiles.json` (and a `secrets.env` for the `${VAR}` its profiles use).
`--workspace <name>`, with `--workspaces-dir <dir>` (or `SOAPCLI_WORKSPACES_DIR`), uses it instead of the profiles file above:

```bash
java -jar SoapCLI.jar --workspaces-dir ./workspaces --workspace billing --profile acceptance --request-file order.xml
```

## Profiles file structure

The profiles file is a JSON object with a top-level `profiles` object whose keys are profile names. Comments
(`// ...`, `/* ... */`) and trailing commas are accepted, which is handy for notes to your future self.

```json
{
  // Settings shared by every environment
  "profiles": {
    "base": {
      "soapVersion": "1.1",
      "headers": { "X-Client": "soapcli" },
      "proxy": { "type": "http", "host": "proxy.example.com", "port": 3128,
                 "user": "proxy-user", "password": "${PROXY_PASSWORD}" },
      "theme": "nord"
    },

    "acceptance": {
      "extends": "base",
      "endpoint": "https://acceptance.example.com/soap",
      "requestFile": "requests/acceptance-default.xml",
      "authBasic": "${ACC_CREDENTIALS}",
      "args": { "ENV": "acceptance" }
    },

    "production": {
      "extends": "base",
      "endpoint": "https://prod.example.com/soap",
      "keystore": { "path": "~/certs/prod.p12", "password": "${PROD_KS_PASSWORD}" },
      "proxy": null          // null removes a value inherited from "base": no proxy for production
    }
  }
}
```

### Complete example with all fields

Below is a single profile that demonstrates **all** available fields:

```json
{
  "profiles": {
    "full-example": {
      "endpoint": "https://api.example.com/soap",
      "requestFile": "requests/example.xml",
      "soapVersion": "1.1",
      "soapAction": "urn:myAction",
      "mtom": false,
      "timeout": 120,
      "connectTimeout": 30,
      "followRedirects": false,
      "headers": { "X-Env": "acceptance" },
      "args": { "ORDER_ID": "42" },
      "attachments": { "message1": "~/files/payload.xml" },
      "authBasic": "${EXAMPLE_CREDENTIALS}",
      "authBearer": "",
      "keystore": {
        "path": "/home/user/certs/client-keystore.p12",
        "type": "PKCS12",
        "alias": "client-cert",
        "password": "${KS_PASSWORD}",
        "aliasPkPass": "${KEY_PASSWORD}"
      },
      "truststore": {
        "path": "/home/user/certs/corp.jks",
        "type": "JKS",
        "password": "${TS_PASSWORD}"
      },
      "insecure": false,
      "proxy": {
        "type": "http",
        "host": "proxy.example.com",
        "port": 3128,
        "user": "proxy-user",
        "password": "${PROXY_PASSWORD}"
      },
      "theme": "nord"
    }
  }
}
```

### Fields

- `profiles` – object mapping profile name → profile config.
    - `extends` – name of another profile to inherit from (see below).
    - `endpoint` – endpoint URL for this profile.
    - `requestFile` – default request file path (optional).
    - `soapVersion` – `1.1`, `1.2` or `auto`. See [SOAP versions](cli-soap-versions.md).
    - `soapAction` – the SOAP action.
    - `mtom` – `true` to send attachments as MTOM.
    - `timeout` / `connectTimeout` – seconds. See [Basic usage](cli-basic-usage.md).
    - `followRedirects` – `true` to follow redirects.
    - `headers` – object of extra HTTP headers.
    - `args` – object of placeholder values (`--arg`). See [Placeholders](cli-placeholders.md).
    - `attachments` – object mapping CID → file path. See [Attachments](cli-attachments.md).
    - `authBasic` – `user:password`. `authBearer` – a bearer token. See [Authentication](cli-auth.md).
    - `keystore` – client certificate (optional): `path`, `type`, `alias`, `password`, `aliasPkPass`.
    - `truststore` – trusted certificates (optional): `path`, `type`, `password`. See [TLS](cli-tls.md).
    - `insecure` – `true` to skip certificate and host-name verification (tests only).
    - `proxy` – proxy configuration (optional): `type` (`http` or `socks`), `host`, `port`, `user`, `password`.
      See [Proxy support](cli-proxy.md).
    - `theme` – default color theme for XML output (optional). See [Themes](cli-themes.md).

Numbers and booleans may also be written as strings (`"port": "3128"`, `"mtom": "true"`), which is useful together with
`${VAR}`. A setting `SoapCLI` does not know (for example a typo such as `timeot`) is ignored with a warning on stderr,
and a value of the wrong type is an error.

Relative paths (`requestFile`, keystore, attachments...) are resolved against the **current directory**, not against the
profiles file; in a [workspace](workspaces.md), against the **workspace folder**. A leading `~/` means your home directory.

## Environment variables in profiles

Any string value can contain references to environment variables:

- `${NAME}` – replaced by the variable's value. If it is not set, the command fails and names the variable and the
  setting that needs it.
- `${NAME:-fallback}` – uses `fallback` when the variable is not set.

```json
"authBasic": "${ACC_CREDENTIALS}",
"headers":   { "X-Env": "${ENV_NAME:-dev}" }
```

This keeps passwords out of a file that might be backed up, synced or shared. References are expanded only when the
profile is **used**; listing and showing profiles never needs the variables.

## Inheritance with `extends`

A profile can start from another one:

```json
"acceptance": { "extends": "base", "endpoint": "https://acceptance.example.com/soap" }
```

- Objects (`headers`, `args`, `proxy`, `keystore`...) are merged key by key; for everything else the child wins.
- A value of `null` removes whatever was inherited (`"proxy": null`).
- Chains are allowed (`c` extends `b` extends `a`); loops are detected and reported.

## Using a profile

Select a profile with `--profile`:

```bash
java -jar SoapCLI.jar \
  --profile acceptance \
  --request-file my-request.xml
```

You can override values from the profile using normal flags. For example,
override the endpoint while keeping everything else from the profile:

```bash
java -jar SoapCLI.jar \
  --profile acceptance \
  --endpoint https://override.example.com/soap \
  --request-file override-request.xml
```

`--header` and `--arg` are **merged** with the profile's `headers` and `args` (the command line wins for the same name).

## Overriding the profiles file location

Use `--profile-file` to specify a different JSON file:

```bash
java -jar SoapCLI.jar \
  --profile production \
  --profile-file /opt/soap-cli/custom-profiles.json \
  --request-file request.xml
```

or set it once for your shell:

```bash
export SOAPCLI_PROFILE_FILE=/opt/soap-cli/custom-profiles.json
```

If `--profile` is specified but the profiles file is missing or malformed, or the profile does not exist (the error then
lists the available ones), the tool fails with a descriptive error.

## Listing available profiles

You can list the names of all profiles defined in a profiles file using `--list-profiles`.
Only the profile names are printed; passwords and other sensitive values are never shown.

```bash
java -jar SoapCLI.jar --list-profiles
java -jar SoapCLI.jar --list-profiles --profile-file /opt/soap-cli/custom-profiles.json
```

## Detailed and single-profile views

For troubleshooting, `--list-profiles-detailed` prints every profile with its **effective** settings (after `extends`)
and `--show-profile <name>` prints one profile as JSON. Both mask passwords, tokens and credential headers; a `${VAR}`
reference is shown as written because it is not a secret.

```bash
java -jar SoapCLI.jar --list-profiles-detailed
java -jar SoapCLI.jar --show-profile production
```

```text
Profiles details (from '/home/user/.config/soap-cli-profiles.json'):
- production:
    extends: base
    soapVersion: 1.1
    headers:
        X-Client: soapcli
    theme: nord
    endpoint: https://prod.example.com/soap
    keystore:
        path: ~/certs/prod.p12
        password: ${PROD_KS_PASSWORD}
```

## Precedence rules

When both profiles and CLI flags are used:

1. The selected profile (from the chosen profiles file, after `extends` and `${VAR}` expansion) is loaded first.
2. CLI flags override the profile's values.

You must end up with a valid configuration:

- `endpoint` must be set (from profile or `--endpoint`).
- If a keystore is configured (profile or CLI), its password must be provided (from either source); the alias is only
  required if the keystore holds several private keys.
- If a proxy is configured, host, port and type must all be set; user and password go together.
