# Installation

## Requirements

- Java **11** or later to run `SoapCLI`. Check with `java -version`. No Java on the machine? Use the
  [Docker image](docker.md) instead.
- To build from source: a JDK 11+ and [Apache Maven](https://maven.apache.org/) 3.8+. Node is **not** needed: the web UI
  is compiled and committed (see below).

## From GitHub releases

Get the pre-built JAR from the [releases page](https://github.com/marcemv90/SoapCLI/releases) and copy it to a location
of your choice. Each release also has a `.sha256` file:

```bash
sha256sum -c SoapCLI-2.1.0.jar.sha256
java -jar SoapCLI-2.1.0.jar --version
```

That's it. Start the [web UI](web-ui.md) with `java -jar SoapCLI-2.1.0.jar serve`.

## Build from source

### 1. Clone the repository

```bash
git clone https://github.com/marcemv90/SoapCLI.git
cd SoapCLI
```

### 2. Build the executable JAR

```bash
mvn clean package
```

This compiles the code, **runs the test suite** and produces a fat JAR (all dependencies included) at:

```text
target/SoapCLI.jar
```

Useful variations:

```bash
mvn clean package -DskipTests            # build without running the tests
mvn clean package -Drevision=2.1.0       # stamp a version, shown by --version
```

!!! note
    The tests start small HTTP/HTTPS servers and a proxy on `127.0.0.1` and use the JDK's `keytool` to create throw-away
    certificates, so they need a JDK (not just a JRE) and no internet access.

### 3. Run `SoapCLI`

```bash
java -jar target/SoapCLI.jar --version
java -jar target/SoapCLI.jar --help
```

Once built, you can copy `target/SoapCLI.jar` anywhere you like and invoke it with `java -jar`.

### 4. (Optional) Build the Docker image

```bash
docker build -t soapcli .
docker run -p 127.0.0.1:8080:8080 soapcli
```

See [Docker](docker.md).

### Changing the web UI

The interface lives in `web-ui/` (React, TypeScript, Tailwind CSS and the free TailAdmin template). Its compiled output
is committed under `src/main/resources/web`, which is why the Maven build needs no Node. To work on it:

```bash
cd web-ui
npm ci
npm run dev        # http://localhost:5173, proxying /api to a running `serve` on port 8080
npm test           # engine tests
npm run build      # recompile into ../src/main/resources/web, then commit the result
```

CI rebuilds the UI and fails if the committed output is out of date. The [architecture notes](architecture.md) explain
how the pieces fit.

## Shortcuts

Typing `java -jar /path/to/SoapCLI.jar` gets old quickly.

**Linux / macOS** – add an alias to your shell profile:

```bash
alias soapcli='java -jar /opt/soapcli/SoapCLI.jar'
```

**Windows** – create `soapcli.cmd` next to the JAR and put that folder on your `PATH`:

```bat
@echo off
java -jar "%~dp0SoapCLI.jar" %*
```

Then `soapcli --profile acceptance --request-file request.xml` works from any directory.

## Windows notes

- Colours are only switched on automatically in terminals known to support them (Windows Terminal, ConEmu, Git Bash,
  ...). In an old `cmd.exe` window you get plain text; force colours with `--color` if yours supports them.
- Quoting differs between `cmd.exe`, PowerShell and bash. Prefer options that need no quotes, such as
  `--soap-action urn:myAction` instead of `--header 'SOAPAction: "urn:myAction"'`.
- The default profiles file is `%USERPROFILE%\.config\soap-cli-profiles.json`; set `SOAPCLI_PROFILE_FILE` to use
  another location (see [Profiles](cli-profiles.md)).
