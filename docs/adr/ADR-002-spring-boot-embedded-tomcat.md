# ADR-002 — Spring Boot embedded Tomcat deployment model

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Repository maintainer
- **Related:** [ADR-001](ADR-001-modular-monolith.md) · [ADR-006](ADR-006-docker-compose-composition.md) · [System overview](../architecture/system-overview.md)

## Context

The application needs an HTTP runtime and a deployment shape. The overall pipeline —
Maven build, Docker image, registry, Ansible provisioning, deploy, health check, rollback —
constrains that choice heavily, because the runtime determines what "an artefact" means and
therefore what "roll back" means.

Two shapes were realistic:

1. A **WAR deployed into an external servlet container** (Tomcat installed on the host or in a
   base image), configured outside the application.
2. A **Spring Boot executable JAR with embedded Tomcat**, where the server is a library inside
   the application.

The pipeline requirement is that the same, immutable, versioned artefact be promoted between
environments. Shape (1) splits the deployable across two independently versioned things: the
WAR *and* the container's configuration, libraries, and JDK. That split is what makes
"works on staging, fails in production" possible, and it makes rollback ambiguous — do you
roll back the WAR, the container config, or both?

## Decision

Package the application as a **Spring Boot executable JAR with embedded Tomcat**, and:

- Run it with `java -jar` on a JRE 21 base image, as a **non-root** user.
- Treat the JAR — and the container image containing it — as the **single deployable unit**.
- Keep all environment-specific configuration **outside** the artefact, supplied as
  environment variables at deploy time.
- Expose one HTTP port; there is no external container to configure.

## Consequences

**Positive**

- **One artefact, one version.** The image tag (the commit SHA) identifies the entire
  runtime. Rollback is "run the previous tag" — unambiguous and fast.
- **Self-contained and startup-verifiable.** The application starts in-process and can fail
  fast on misconfiguration, before the pipeline's health check is reached.
- **Reproducible locally.** A developer runs the same artefact that runs in production.
- Embedded Tomcat is configured in code and version-controlled, so a server tuning change is a
  reviewable commit rather than a change on a host.
- Spring Boot's health endpoint and graceful shutdown integrate directly with the deployment
  and health-check stages.

**Negative / accepted costs**

- **The server is upgraded with the application.** A Tomcat CVE means rebuilding and redeploying
  the application, not patching a shared container. Accepted: a rebuild is one pipeline run,
  and it keeps the server version pinned to the application version rather than drifting.
- **No shared container to host several applications.** If a second application ever needs
  hosting, this shape does not provide it. Out of scope — this is one application.
- **Container image size** is larger than a thin WAR against a prepared base image. Mitigated
  by a layered Dockerfile that caches the dependency layer separately from application classes.
- Embedded-server tuning is a code change, so operators cannot retune it without a build.
  Accepted deliberately: the configuration stays reviewable and drift-free.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| **WAR on an external Tomcat** | Splits the deployable across two independently versioned artefacts and makes rollback ambiguous. Its advantages (shared container, config-time tuning) address problems this project does not have. |
| **Embedded Jetty or Undertow** | Technically equivalent. Spring Boot's default and best-supported embedded container is Tomcat; choosing otherwise would add variance for no benefit. |
| **A JAR plus a sidecar reverse proxy** | Adds a process and a configuration surface. A reverse proxy may still be introduced at the host level for TLS termination, but it is not part of the deployable. |
| **GraalVM native image** | Attractive startup time, but Spring Security, JPA/Hibernate, and reflection-heavy configuration make native builds a significant, still-maturing undertaking. It would consume the pipeline budget for no requirement. |

## Follow-up

The Dockerfile must run the application as a non-root user and must not bake configuration or
secrets into image layers. This is a review requirement in
[`CLAUDE.md` §7](../../CLAUDE.md), not merely a preference.
