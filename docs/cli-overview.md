# Command-line usage

This section explains how to use `SoapCLI` from the command line, including:

- [Basic invocation](cli-basic-usage.md) and providing the SOAP request body.
- [SOAP 1.1 and 1.2](cli-soap-versions.md), `SOAPAction` and custom headers.
- [Sending attachments](cli-attachments.md) (inline Base64 and MTOM/XOP).
- [Placeholders](cli-placeholders.md) to parameterize one request file.
- [HTTP authentication](cli-auth.md) (Basic, Bearer) and [TLS](cli-tls.md) (client certificates, trust, self-signed
  servers).
- Using [profiles](cli-profiles.md) to avoid repeating configuration.
- Working behind HTTP/HTTPS and SOCKS [proxies](cli-proxy.md).
- [Output options](cli-output.md) (colours, raw output, files) and [themes](cli-themes.md).
- Understanding [return codes and errors](cli-rc-and-errors.md) when scripting.
- Seeing what is really sent with [dry-run and debug](cli-debug.md).

Run `java -jar SoapCLI.jar --help` for the complete, always up-to-date list of options.
