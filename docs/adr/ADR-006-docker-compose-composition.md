# ADR-006 — Docker Compose for local and deployment composition

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Repository maintainer
- **Related:** [ADR-002](ADR-002-spring-boot-embedded-tomcat.md) · [ADR-004](ADR-004-filesystem-volume-and-db-metadata.md) · [System overview §5](../architecture/system-overview.md)

## Context

Two related but distinct needs must both be satisfied:

1. **A developer must be able to run the whole system on a laptop**, with the same MySQL
   version and the same storage semantics as production, in one command, without installing
   MySQL.
2. **The deployment target must be reproducible and idempotent**, because Ansible provisions
   it and the pipeline re-runs against it. A deployment that leaves residue or behaves
   differently on the second run is not a deployment, it is a series of incidents.

The running system is small: **one application container and one database container on one
host**, with two volumes. The database must not be reachable from outside the host. The
application must be able to reach the database by name.

Kubernetes is the modern default answer to "how do I run containers", and it is the wrong
answer here. It would require a cluster to provision and maintain, manifests or Helm charts to
write, an ingress and a storage class to configure, and it would replace the Ansible
provisioning stage the assignment asks for. It solves scheduling, scaling, and multi-tenancy
problems this project does not have — for one application on one host.

## Decision

Use **Docker Compose** to define the running system in **both** contexts — local development
and the deployment host — from **one committed Compose file**, with environment-specific
values supplied from outside it:

- **Services:** `app` (the Spring Boot image) and `db` (MySQL 8.4).
- **Named volumes:** `app-uploads` and `mysql-data`, so `docker compose down` preserves state
  (see [ADR-004](ADR-004-filesystem-volume-and-db-metadata.md)).
- **Network:** a private Compose network. The application reaches the database by service name
  (`db`), not by IP. **Only the application port is published**; the database port is not
  exposed to the host.
- **Health and ordering:** the `db` service declares a healthcheck; `app` waits for the
  database to be healthy before starting, so startup does not race the database and the
  application's fail-fast startup check is not spuriously triggered.
- **Configuration:** all environment-specific values arrive as environment variables — from
  `.env` locally, and from Ansible/secret injection at deploy time. **No secret is written into
  the Compose file.**
- **Images are referenced by immutable tag**, never `latest`, so a deployment is traceable to a
  commit.

Ansible's responsibility is to **provision the host and drive Compose** — installing Docker,
placing the Compose file and environment, running `compose up`, health-checking, and rolling
back. Ansible does not reimplement what Compose already declares.

### Local development clarification (2026-10-01)

The "database port is not published" rule above governs the **deployment** composition. Local
development is a different context: a developer runs the application on the host with
`./mvnw spring-boot:run`, so the host must be able to reach MySQL.

`deploy/docker-compose.yml` therefore publishes the database port on **loopback only**
(`127.0.0.1:${DB_PORT:-3306}:3306`). The decision is unchanged — the database remains absent
from every other network interface, and the deployment composition continues to publish no
database port at all.

This is a clarification of scope, not a change to the decision.

## Consequences

**Positive**

- **One file describes the system, in both places.** Local and deployed topologies cannot
  silently drift, because they are the same declaration with different values.
- **The database is not exposed.** "The database port is not published" is a security property
  enforced by the topology rather than by a firewall rule someone must remember.
- **Idempotence for free.** `docker compose up -d` converges to the declared state; re-running
  it is safe. This is exactly the property the pipeline's repeated Ansible runs require.
- **State survives redeployment** via named volumes, which is what makes
  [ADR-004](ADR-004-filesystem-volume-and-db-metadata.md) actually deliver on its promise.
- **Low operational surface.** A developer needs Docker and one command; an operator needs
  Docker and one command.
- **Ansible stays in its lane.** Provisioning the host and injecting configuration is real
  work; hand-rolling container lifecycle management in Ansible tasks would duplicate Compose
  and drift from it.

**Negative / accepted costs**

- **Single-host only.** Compose does not schedule across machines or reschedule a failed
  container onto another node. Accepted: the deployment is one instance on one host, and
  `restart: unless-stopped` covers process-level failure. Multi-host would need an orchestrator
  — explicitly out of scope (`mvp.md` §3 #12).
- **No zero-downtime rolling deploy.** Replacing the container is a brief outage. Accepted for
  a course-sized application; the health check and rollback guarantee *correctness* of the
  outcome, not absence of downtime.
- **Secrets are still environment files on the host.** Compose itself has no secret store, so
  protection depends on Ansible Vault or host permissions. This is a genuine residual risk,
  recorded rather than hidden.
- **The Compose file is a shared configuration surface** between local and deployed contexts.
  A change made for one environment can affect the other. Mitigated by keeping the committed
  file to structure only, with every environment-specific value externalised.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| **Kubernetes** | Solves scheduling, scaling, and multi-tenancy for a workload that has none of those problems. Requires a cluster, manifests, ingress, and storage classes, and displaces the Ansible provisioning stage the assignment requires. Disproportionate by an order of magnitude. |
| **Run the JAR directly on the host, MySQL installed natively** | No container consistency between local and deployed environments, and Ansible would manage services, users, and directories per host. Reproducibility is much weaker. |
| **Docker Compose locally, Kubernetes in production** | The worst option: two deployment topologies to maintain, and the environment that developers test against is not the one that ships. |
| **`docker run` driven entirely by Ansible tasks** | Reimplements service declarations, ordering, volumes, and networking as imperative tasks, which then drift from the code. Compose declares the same thing declaratively and idempotently. |
| **Podman / Compose-compatible alternatives** | Viable, but Docker is the stack fixed by the brief and the toolchain the pipeline targets. No constraint forces a change. |

## Follow-up

- A healthcheck must exist for both services, so `app` can wait for `db` and the pipeline's
  deployment stage can gate on the application's own health endpoint.
- Backup and restore procedures for both volumes must be written before the first production
  deployment — a volume that has never been restored is an untested assumption.
