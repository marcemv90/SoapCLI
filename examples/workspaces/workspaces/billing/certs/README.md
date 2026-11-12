# Certificates

Put here the keystore and truststore the profiles of this workspace use. A relative path in a profile starts in the
workspace folder, so this is `certs/`:

```json
"keystore":   { "path": "certs/client.p12",         "type": "PKCS12", "password": "${KEYSTORE_PASSWORD}" },
"truststore": { "path": "certs/corp-truststore.jks", "type": "JKS",    "password": "${TRUSTSTORE_PASSWORD}" }
```

Passwords come from `secrets.env` in the workspace folder (`${NAME}`), not from the profile.

The `production-mtls` profile points at `certs/client.p12`: it will not work until that file is here.

Making a PKCS12 keystore from a certificate and its key in PEM:

```bash
openssl pkcs12 -export -inkey client-key.pem -in client-cert.pem -out client.p12
```

The `.gitignore` of the example keeps `*.p12`, `*.jks`, `*.pfx` and the like out of version control. Do not commit them.
