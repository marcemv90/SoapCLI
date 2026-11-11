# Authentication

This page covers HTTP-level authentication. For client certificates and trusting servers, see [TLS](cli-tls.md).

## HTTP Basic authentication

Some SOAP endpoints require HTTP Basic authentication at the transport level. Send a `Basic` `Authorization` header with
either a direct value or an environment variable:

- `--auth-basic user:password` – sets `Authorization: Basic ...` using the given `user:password`.
- `--auth-basic-env VAR` – reads `user:password` from the environment variable `VAR`.

If both options are used, the explicit `--auth-basic` value takes precedence over `--auth-basic-env`, whatever their
order. Credentials are sent preemptively, in UTF-8.

Example (direct value):

```bash
java -jar SoapCLI.jar \
  --endpoint https://secure.example.com/soap \
  --request-file request.xml \
  --auth-basic user:password
```

Example (environment variable):

```bash
export SOAPCLI_BASIC_AUTH='user:password'

java -jar SoapCLI.jar \
  --endpoint https://secure.example.com/soap \
  --request-file request.xml \
  --auth-basic-env SOAPCLI_BASIC_AUTH
```

## Bearer tokens

- `--auth-bearer <token>` – sends `Authorization: Bearer <token>`.
- `--auth-bearer-env VAR` – reads the token from the environment variable `VAR`.

Basic and Bearer cannot be combined. For any other scheme use `--header "Authorization: ..."`.

## Profiles

A profile can hold `authBasic` or `authBearer`, ideally as an environment variable reference so the secret is not stored
in the file:

```json
{ "profiles": { "acceptance": { "endpoint": "https://acc.example.com/soap", "authBasic": "${ACC_CREDENTIALS}" } } }
```

See [Profiles](cli-profiles.md).

## Keeping credentials safe

- `--dry-run`, `--debug` and `-i` mask `Authorization`, `Cookie` and similar headers (`Authorization: Basic ****`).
  Use `--show-secrets` only when you really need the values.
- `--show-profile` and `--list-profiles-detailed` mask secrets too; a `${VAR}` reference is shown as written because it
  is not a secret.
- Credentials are **never** sent to another host: when `--follow-redirects` follows a redirect to a different host, port
  or scheme, `Authorization` and `Cookie` are dropped.

!!! warning
    Putting credentials directly on the command line means they may appear in your shell history or process listings.
    Prefer the `-env` variants or `${VAR}` references in profiles, consider dedicated test accounts, and avoid reusing
    production passwords. Note that `--debug` also prints the **request body**, which may itself contain credentials
    (WS-Security headers, for instance).
