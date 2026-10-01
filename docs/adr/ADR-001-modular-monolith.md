# ADR-001 — Modular monolith architecture

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Repository maintainer
- **Related:** [ADR-002](ADR-002-spring-boot-embedded-tomcat.md) · [ADR-003](ADR-003-mysql-system-of-record.md) · [Application structure](../architecture/application-structure.md)

## Context

The portal has three actors, one artefact type, and a two-step workflow. Its engineering
objective is a **repeatable delivery pipeline** — build, test, containerise, provision,
deploy, health-check, roll back — not a distributed system.

The default industry reflex for a "production-style" project is microservices. That reflex
should be resisted here, because the constraint that motivates microservices does not exist:

- There is **one team**, so independent deployability buys nothing.
- There is **no independent scaling axis**, so per-service scaling buys nothing.
- There is **one database**, so services would share a schema anyway — the worst of both.
- Every service boundary would multiply the pipeline's surface: N Docker images, N
  deployments, N health checks, N rollback paths, and network failure modes in place of method
  calls.

Meanwhile the pipeline is the deliverable. Every hour spent on service discovery, distributed
tracing, and inter-service contracts is an hour not spent on the pipeline the assignment is
actually about.

## Decision

Build a **single deployable modular monolith**:

- One codebase, one build, one executable JAR, one container.
- Internal structure is **package-by-feature** with a strict, acyclic dependency direction
  ([application-structure.md](../architecture/application-structure.md)).
- Module boundaries are enforced by **convention and review**, not by the network.
- A single relational database and a single transaction boundary per business operation.

## Consequences

**Positive**

- A business operation that touches submissions, history, and audit is **one transaction**. In
  a distributed design this would be a saga.
- The pipeline has exactly one artefact to build, tag, scan, ship, health-check, and roll
  back. The rollback story is "start the previous image."
- Refactoring across a boundary is a rename, not a versioned API migration. The boundaries can
  be corrected cheaply while the domain is still being learned.
- Local development is `docker compose up` plus one JVM.

**Negative / accepted costs**

- **All modules scale together.** One noisy workload affects everything. Acceptable: the load
  is course-sized.
- **A module boundary can be violated silently**, because the compiler does not stop a
  developer from importing another feature's repository. Mitigation: the review checklist in
  [application-structure.md §5](../architecture/application-structure.md), and the small size
  of the codebase.
- **One deployment risks the whole system.** Mitigated by the health-check-and-rollback stage,
  which is a required pipeline stage rather than an optional one.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| **Microservices from the start** | Multiplies pipeline surface and introduces distributed-systems failure modes for a workload with one team and one database. Directly opposes the project's objective. |
| **Classic layered monolith** (top-level `controller/`, `service/`, `repository/`) | Simpler to start, but scatters each feature across the tree and gives no place to put a boundary. Package-by-feature costs nothing extra and keeps the option of extraction open. |
| **Modular monolith with enforced module boundaries** (Spring Modulith, ArchUnit) | Genuinely attractive — ArchUnit tests would catch boundary violations in CI. Rejected *for the MVP* as an extra dependency and learning surface; revisit if boundary violations become a recurring review problem. |
| **Serverless functions** | Requires a fundamentally different runtime, storage, and session model, and removes the container pipeline the assignment is about. |

## Follow-up

A future extraction of a module into a service would require a superseding ADR. The
package structure is deliberately arranged so that such an extraction is a packaging change
rather than a rewrite — but nothing in the MVP anticipates it.
