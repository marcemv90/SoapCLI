# Certificates

Put here the keystore and truststore your profiles use. Inside the container this folder is `/config/certs`, which is
the path to write in a profile:

```json
"keystore":   { "path": "/config/certs/client.p12",        "type": "PKCS12", "password": "${KEYSTORE_PASSWORD}" },
"truststore": { "path": "/config/certs/corp-truststore.jks", "type": "JKS",    "password": "${TRUSTSTORE_PASSWORD}" }
```

Passwords come from `soapcli.env` (`${NAME}` is read from the container's environment), not from the profile.

The `production-mtls` profile of `../profiles.json` points at `client.p12`: it will not work until that file is here.

Making a PKCS12 keystore from a certificate and its key in PEM:

```bash
openssl pkcs12 -export -inkey client-key.pem -in client-cert.pem -out client.p12
```

The `.gitignore` of the example keeps `*.p12`, `*.jks`, `*.pfx` and the like out of version control. Do not commit them.
