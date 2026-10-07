# ===========================================================================
# Production image for the Online Assignment Portal.
#
# This file does not compile anything.
#
# The JAR it packages is the one `./mvnw clean verify` already produced at
# `target/`. That is a constraint, not a shortcut: the artefact the suites
# validated and the artefact that runs in the container have to be the same
# bytes, or "the tests passed" is a statement about a different program. A Maven
# build inside this file would be a second build process, would resolve
# dependencies without the wrapper's pinned Maven, and could disagree with the
# JAR the pipeline tested (CLAUDE.md §1, §10).
#
# Build from the repository root, after the build has produced the artefact:
#
#   ./mvnw clean package -DskipTests
#   docker build -t online-assignment-portal:local .
#
# Full lifecycle, and what survives what: docs/operations/docker.md.
# ===========================================================================

# ---------------------------------------------------------------------------
# The Java runtime the application runs on.
#
# Eclipse Temurin 21 is a maintained, freely redistributable build of OpenJDK
# 21 (LTS) — the same major version the build enforces (`maven-enforcer-plugin`,
# `requireJavaVersion [21,22)`) and the same one the suites ran under.
#
# The tag names the major version and the package variant, and nothing newer.
# `latest` is banned: it makes an image irreproducible, because the same file
# builds something different next month. The exact patch release is not written
# here either, because a superseded patch tag can no longer be pulled — a stale
# pin turns into a build that fails, which is not the same thing as a build that
# repeats.
#
# Immutable pinning. `docker buildx imagetools inspect eclipse-temurin:21-jre`
# prints the digest this tag currently resolves to. Appending it as `@sha256:…`
# to the value below freezes the base image against the tag being re-pointed
# upstream, which is the only way a rebuild of this file is guaranteed to
# produce the same image. The digest actually in use is recorded in
# docs/operations/docker.md §2.
#
# The value is overridable so that pinning — or a mirrored registry — is a build
# argument rather than an edit here:
#
#   docker build --build-arg JAVA_BASE_IMAGE=eclipse-temurin:21-jre@sha256:… .
# ---------------------------------------------------------------------------
ARG JAVA_BASE_IMAGE=eclipse-temurin:21-jre

# ---------------------------------------------------------------------------
# Stage 1 — unpack the executable JAR into its layers.
#
# Spring Boot's repackager does not merely gather the dependencies into one
# archive; it groups their contents into layers, ordered so that what changes
# least is written first:
#
#     dependencies/           third-party JARs, unchanged between most commits
#     spring-boot-loader/     the launcher, unchanged between Boot upgrades
#     snapshot-dependencies/  -SNAPSHOT dependencies; empty in this build
#     application/            this project's classes and resources
#
# Stage 2 copies those four directories as four separate Docker layers. The
# effect is that a rebuild after a change to one Java class rewrites the
# `application/` layer and reuses the other three from the cache, instead of
# re-writing a ~60 MB archive as a single layer on every commit.
#
# The stage is otherwise disposable: only those four directories cross into
# stage 2, so the unpacked JAR's remaining metadata costs the final image
# nothing.
# ---------------------------------------------------------------------------
FROM ${JAVA_BASE_IMAGE} AS unpack

WORKDIR /unpack

# Named exactly, not globbed. `target/*.jar` would also match
# `<name>.jar.original` — the sidecar the Spring Boot plugin leaves beside the
# executable JAR, and a plain archive with no launcher and no nested
# dependencies. A wildcard that matched two files would put both into the
# context and leave the choice of which one runs to chance.
COPY target/online-assignment-portal-0.0.1-SNAPSHOT.jar application.jar

# `tools` is the Spring Boot 3.3+ name for the JAR's own command interface; the
# older `layertools` no longer exists in Boot 4. The layers land in
# ./extracted/<layer>/.
#
# The `mkdir` covers the one layer that can legitimately be empty. This project
# depends on no -SNAPSHOT artefacts, so whether the extractor writes an empty
# `snapshot-dependencies/` at all is an implementation detail of the version in
# use — and a COPY whose source directory does not exist fails the build.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted \
 && mkdir -p extracted/snapshot-dependencies

# ---------------------------------------------------------------------------
# Stage 2 — the runtime image.
# ---------------------------------------------------------------------------
FROM ${JAVA_BASE_IMAGE}

# curl, and only curl.
#
# The image has to be able to answer "is this container well?" for its own
# HEALTHCHECK below, and for `docker ps` to show anything other than "none". The
# Temurin images ship no HTTP client, so a probe written against one would not
# run. curl is an operational probe and not a development tool: the image still
# contains no compiler, no build tool, no package manager cache and no source.
# `--no-install-recommends` and the removal of /var/lib/apt/lists keep the
# addition to the binary and its own dependencies.
#
# Where a base image genuinely cannot carry a probe — a distroless runtime, for
# instance — the honest outcome is a container with no HEALTHCHECK, and the
# startup-ordering guarantee is lost with it. This image takes the probe.
RUN apt-get update \
 && apt-get install --yes --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# An unprivileged account for the process, created rather than borrowed.
#
# The Temurin images have no application user, and a container whose process is
# uid 0 hands anything that escapes the JVM the host's root. A fixed uid/gid
# rather than an auto-assigned one, so the identity is the same in every
# environment and a volume written by one deployment is writable by the next.
RUN groupadd --gid 1001 portal \
 && useradd --uid 1001 --gid portal --no-create-home --home-dir /app \
      --shell /usr/sbin/nologin portal

WORKDIR /app

# The four layers, in dependency order …
COPY --from=unpack --chown=portal:portal /unpack/extracted/dependencies/ ./
COPY --from=unpack --chown=portal:portal /unpack/extracted/spring-boot-loader/ ./
COPY --from=unpack --chown=portal:portal /unpack/extracted/snapshot-dependencies/ ./
COPY --from=unpack --chown=portal:portal /unpack/extracted/application/ ./

# … and the one directory that must stay writable.
#
# The storage root is a volume (ADR-004). Creating it here and giving it to the
# process account means a container started *without* a volume mounted on it
# still writes successfully. That degrades to "the data is in the container
# filesystem" — wrong, but recoverable — instead of failing at startup with a
# permission error that reads like an application bug.
RUN mkdir -p /var/lib/portal/uploads && chown portal:portal /var/lib/portal/uploads

USER portal

# The port the application binds when nothing says otherwise
# (`server.port: ${APP_PORT:8080}`, application.yml). Declared so that
# `docker run -P` and Compose can publish it without being told, and so the port
# is documentation rather than folklore. It publishes nothing by itself: EXPOSE
# records an intent, and a container stays unreachable until something maps the
# port.
EXPOSE 8080

# How to ask this container whether it is well.
#
# The endpoint is the one the delivery pipeline's health-check stage uses
# (FR-25), so the container's notion of healthy and the pipeline's are the same
# question asked once. `--fail` makes curl exit non-zero on a 5xx, and Actuator
# answers 503 when a component it knows about is down — so a container whose
# database has gone away reports unhealthy rather than merely "running".
#
# The port is read from the environment with the same default as `server.port`,
# so an APP_PORT override moves the probe with the application instead of
# leaving it pointing at nothing.
#
# The start period covers Flyway migrating the schema and the context coming up.
# Without it, a slow first start would be reported as a failure the container
# then acts on.
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=6 \
    CMD curl --fail --silent --show-error --output /dev/null \
        "http://localhost:${APP_PORT:-8080}/actuator/health"

# The version this image is.
#
# Baked in at build time rather than injected at run time, because the two are
# not equivalent. The release pipeline runs
#
#   docker build --build-arg APP_VERSION=1.0.1 -t <repository>:1.0.1 .
#
# so the tag and the value here come from one variable in one command, and a
# deployment that pulls `<repository>:1.0.1` gets an image that reports 1.0.1
# whatever its own configuration says. A version read from the deployment's
# environment would instead be a claim the deployment makes about itself, and a
# deployment running a stale image would make it just as confidently.
#
# The application publishes it at /actuator/info (application.yml), which is what
# lets scripts/ops/smoke-test.ps1 prove that the release which was deployed is the
# release that is answering.
#
# `unknown` is the honest default for a build that was not given one — an image
# built from a working tree rather than from a tag. It is deliberately not empty:
# an empty value would be indistinguishable from "the property was never set".
#
# This is NOT environment configuration and must not become it. The environment
# name is a deployment's business and arrives from deploy/compose.prod.yaml, so
# that one artefact can be promoted between environments without being rebuilt
# (CLAUDE.md §8).
ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}

# Heap sizing from the container's limit, not the host's memory.
#
# Without this the JVM sizes its heap from the host's physical memory, ignores
# `--memory`, and is OOM-killed by the kernel at a fraction of the load it
# believed it could carry. The JVM reads JAVA_TOOL_OPTIONS from the environment
# on its own, which is why this can be a variable rather than part of the
# command below: it keeps the entry point an exec-form `java` invocation with no
# shell in between, and it means an operator can raise or replace the value with
# `docker run -e JAVA_TOOL_OPTIONS=…` without touching the image.
#
# The JVM prints `Picked up JAVA_TOOL_OPTIONS: …` on stderr at every start when
# this is set. That line is the mechanism working, not a warning.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"

# Graceful shutdown, and why the entry point is shaped the way it is.
#
# `server.shutdown: graceful` (application.yml) makes Tomcat stop accepting new
# requests on SIGTERM and drain the ones in flight before the context closes.
# None of that happens unless the signal reaches the JVM, and the exec form
# below is what ensures it: `ENTRYPOINT ["java", …]` makes the JVM pid 1, which
# is the process the container runtime signals. A shell form
# (`ENTRYPOINT java …`) would put /bin/sh at pid 1, and neither sh nor bash
# forwards SIGTERM to its child — the container would be killed outright when
# the grace period expired, cutting off every in-flight request.
#
# STOPSIGNAL is SIGTERM by default. It is stated because the drain above depends
# on it, so that changing it has to be a deletion rather than an omission.
STOPSIGNAL SIGTERM

# There is no JAR path here because there is no JAR: stage 1 unpacked the archive
# into /app, and JarLauncher is the class the executable JAR's own Main-Class
# delegates to. It loads the layers from the working directory, which is why the
# WORKDIR above is load-bearing.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
