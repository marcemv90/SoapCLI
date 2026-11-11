# TLS: client certificates, trust and self-signed servers

## Client certificates (mutual TLS)

For HTTPS endpoints that require a client certificate, give `SoapCLI` a keystore that holds it:

- `--keystore <file>` – keystore path (JKS or PKCS12).
- `--keystore-type <type>` – `JKS` or `PKCS12`. If omitted it is inferred from the file extension (`.p12`, `.pfx`,
  `.pkcs12` → PKCS12, anything else → JKS).
- `--keystore-alias <alias>` – which private key to use. **Optional** when the keystore holds exactly one private key;
  matching is case-insensitive.
- `--keystore-password <password>` or `--keystore-password-env VAR` – keystore password (required with `--keystore`).
  It is also used as the key password unless you give another one.
- `--keystore-alias-pkpass <password>` or `--keystore-alias-pkpass-env VAR` – private key password if different from the
  keystore password.

```bash
java -jar SoapCLI.jar \
  --endpoint https://secure.example.com/soap \
  --request-file request.xml \
  --keystore /home/user/certs/client.p12 \
  --keystore-password-env CLIENT_KS_PASSWORD
```

Mistakes are reported before anything is sent, in plain words:

- *Alias 'x' not found in the keystore 'k.p12'. Available private keys: client, other*
- *The keystore 'k.p12' has several private keys; choose one with --keystore-alias. Available: ...*
- *Could not open the keystore 'k.p12' as PKCS12: ... (wrong password or type? use --keystore-type)*

## Trusting the server

By default `SoapCLI` trusts what the JVM trusts (the `cacerts` file of your Java installation), honouring the standard
`-Djavax.net.ssl.trustStore` / `-Djavax.net.ssl.trustStoreType` / `-Djavax.net.ssl.trustStorePassword` system
properties. An internal CA, or a self-signed test server, is not in there, and the call fails with:

```text
Error: TLS handshake with test.example.com:8443 failed: the server certificate is not trusted. Trust its CA with
--truststore <file>, or use --insecure for a throw-away test. (...)
```

### Option 1: a truststore (recommended)

- `--truststore <file>` – JKS or PKCS12 file with the certificates (CAs or the server certificate itself) to trust
  **instead of** the JVM default.
- `--truststore-type <type>` – inferred from the extension if omitted.
- `--truststore-password <password>` or `--truststore-password-env VAR`.

Create one from your company's CA certificate with the JDK's `keytool`:

```bash
keytool -importcert -alias corp-ca -file corp-ca.cer -keystore corp-truststore.jks -storepass changeit -noprompt
```

and use it:

```bash
java -jar SoapCLI.jar --endpoint https://test.example.com/soap --request-file request.xml \
  --truststore corp-truststore.jks --truststore-password changeit
```

You can combine `--keystore` and `--truststore`.

!!! tip
    Put these in a [profile](cli-profiles.md) once (`"truststore": {"path": "...", "password": "${TS_PASSWORD}"}`)
    and forget about them.

### Option 2: `--insecure` (throw-away tests only)

`--insecure` skips **both** the certificate validation and the host-name check. A warning is printed every time:

```text
Warning: TLS certificate and host name verification are DISABLED (--insecure). Do not use this against production systems.
```

With it, anyone on the network path can read or alter the traffic. Use it only to find out whether a call works at all
against a test server, then set up proper trust.

## Profiles

```json
{
  "profiles": {
    "secure": {
      "endpoint": "https://secure.example.com/soap",
      "keystore":   { "path": "~/certs/client.p12", "password": "${CLIENT_KS_PASSWORD}" },
      "truststore": { "path": "~/certs/corp.jks",   "password": "${CORP_TS_PASSWORD}" }
    }
  }
}
```

`~` at the start of a path is expanded to your home directory. See [Profiles](cli-profiles.md).

## Troubleshooting

| Message | Likely cause |
|---------|--------------|
| *the server certificate is not trusted* | Missing CA: use `--truststore` (or `--insecure` for a quick test). |
| *the certificate does not match the host name* | You connect by IP or an alias the certificate does not list. Use the host name in the certificate, or `--insecure` for a test. |
| *The server may require a client certificate (--keystore) or not trust the one you sent* | The server demanded a client certificate and none was sent, or it is not signed by a CA the server trusts. |
| *Could not unlock the private key* | The key password differs from the keystore password: use `--keystore-alias-pkpass`. |
