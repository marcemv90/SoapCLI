# syntax=docker/dockerfile:1

# SoapCLI image.
#   docker run -p 8080:8080 IMAGE                        -> the web UI (the default)
#   docker run --rm -v "$PWD:/work" IMAGE --endpoint ... -> the command line client, no Java needed on the host
#   docker run IMAGE serve --port 9000 ...               -> the web UI with options
#
# The web UI is already compiled and committed under src/main/resources/web, so this build needs only Maven + a JDK.

# ---- build the jar ---------------------------------------------------------------------------------------------------
# The jar is the same on every platform, so it is built once, natively, on the machine that runs the build
# ($BUILDPLATFORM) instead of once per target architecture (an emulated Maven build would take many minutes).
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Dependencies first, so that changing the code does not download them again.
COPY pom.xml .
RUN mvn -B -ntp -q dependency:go-offline

COPY src ./src
# Optional: --build-arg VERSION=1.2.3 stamps the version shown by --version (default: the one in the pom).
ARG VERSION=
RUN mvn -B -ntp -q -DskipTests ${VERSION:+-Drevision=$VERSION} package \
 && cp target/SoapCLI.jar /SoapCLI.jar

# ---- run -------------------------------------------------------------------------------------------------------------
# The jar targets Java 11 but runs on any newer JRE; the current LTS gets the security updates. This stage is built for each
# target platform (linux/amd64 and linux/arm64 are published, so Intel/AMD machines and Apple Silicon / ARM servers both work).
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="SoapCLI" \
      org.opencontainers.image.description="A SOAP client: a web UI by default, the command line client on demand" \
      org.opencontainers.image.source="https://github.com/marcemv90/SoapCLI" \
      org.opencontainers.image.documentation="https://marcemv90.github.io/SoapCLI/"

# Never run as root. /work is where the command line client finds your files (mount them there);
# /config is where the web UI finds the profiles file (mount it there).
RUN useradd --system --uid 10001 --user-group --create-home --home-dir /home/soapcli --shell /usr/sbin/nologin soapcli \
 && mkdir -p /app /work /config \
 && chown soapcli:soapcli /work /config

COPY --from=build /SoapCLI.jar /app/SoapCLI.jar

# Inside a container the UI must listen on all interfaces; what is reachable from outside is decided by `-p`.
ENV SOAPCLI_HOST=0.0.0.0 \
    SOAPCLI_PORT=8080 \
    SOAPCLI_PROFILE_FILE=/config/profiles.json

USER soapcli
WORKDIR /work
EXPOSE 8080

# The image is only "healthy" while a web UI answers; a command line run is over long before the first check.
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD ["java", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-jar", "/app/SoapCLI.jar", "healthcheck"]

# JVM flags live here (not in JAVA_TOOL_OPTIONS, which would print a notice on every command line run).
# Arguments given to `docker run` replace CMD, so any argument that is not `serve` runs the command line client.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/SoapCLI.jar"]
CMD ["serve"]
