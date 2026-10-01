# ADR-003 — MySQL as system of record

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Repository maintainer
- **Related:** [ADR-004](ADR-004-filesystem-volume-and-db-metadata.md) · [Data model](../architecture/data-model.md) · [MVP requirements](../requirements/mvp.md)

## Context

The portal stores users, submissions, an append-only status history, and an audit trail. The
data has strong relational structure and hard integrity requirements:

- A submission **must** reference an existing user.
- A status transition **must** reference an existing submission and an existing actor.
- The status history is a log whose value depends on being complete and append-only.
- A submission must be decided **at most once** — which requires a transaction with a
  concurrency guarantee, not a best-effort check.

Several storage options were on the table: MySQL, PostgreSQL, H2, or an embedded document
store. The stack was fixed by the brief to MySQL 8.4 unless a repository constraint forced a
change; no such constraint exists. The question is therefore less "which database" than "what
role does the database play, and what does the application rely on it for."

## Decision

Use **MySQL 8.4 (LTS) accessed through Spring Data JPA with Hibernate**, and treat it as the
**system of record** for all structured data:

- The database enforces referential integrity with **real foreign keys**, not application-level
  conventions.
- Business invariants that can be expressed as constraints — status values, size limits, the
  decided-or-not shape — are expressed as **`CHECK` constraints** in addition to service-layer
  validation. MySQL 8.4 enforces them; earlier MySQL versions silently ignored `CHECK` and are
  therefore not acceptable.
- Schema is created and evolved **only** by versioned **Flyway** migrations.
- Hibernate runs with **`ddl-auto=validate`**. It never creates or alters the schema.
- Concurrency on the decision path is handled by a **JPA optimistic lock** (`@Version`), so a
  double-decision race fails one transaction rather than corrupting state.

## Consequences

**Positive**

- **Integrity is not optional.** A foreign key and a `CHECK` constraint hold even if a future
  code path forgets a rule. Application bugs become rejected writes instead of corrupt data.
- **Flyway migrations are reviewable artefacts.** Every schema change is a diff in a pull
  request, applied identically to every environment. This is what makes the pipeline's
  promotion of a single artefact meaningful.
- **The same engine runs everywhere.** Tests, local development, and production all use
  MySQL 8.4, so an integration test proves the real thing rather than an approximation.
- **MySQL 8.4 is an LTS release**, so the version is stable for the life of the project and
  has a published support window.
- The relational model fits the domain exactly: the history and audit tables are naturally
  relational, and the reporting queries (a reviewer's queue) are simple joins.

**Negative / accepted costs**

- **`CHECK` constraints make MySQL 8.4 a genuine floor.** The project cannot silently fall back
  to MySQL 5.7. This is intended — the alternative is constraints that do not enforce.
- **Optimistic locking pushes conflict handling into the application.** A concurrent decision
  raises an optimistic-lock failure that the service must translate into a friendly "this
  submission has already been decided" message. Accepted: it is a small, explicit amount of
  code, and the alternative (pessimistic row locks) couples the request to a held transaction.
- **Migrations are forward-only and operationally significant.** A bad migration is a real
  incident. Mitigated by testing migrations in the integration suite against a real MySQL
  container, and by keeping migrations small.
- **The JPA abstraction hides SQL**, which can produce surprising queries. Accepted for the
  MVP's simple access patterns; a hotspot would be replaced with an explicit query, not
  worked around.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| **PostgreSQL** | Technically excellent and arguably a better fit for the constraint story. Rejected because the stack is fixed to MySQL 8.4 and no repository constraint forces a change. Swapping would add no capability the requirement asks for. |
| **H2 in-memory for tests, MySQL in production** | The single most common source of "passes in CI, fails in production". It tests a different engine's SQL dialect, constraint behaviour, and transaction semantics. Rejected in favour of **Testcontainers running real MySQL 8.4** — see [testing strategy](../testing/strategy.md). |
| **Hibernate `ddl-auto=update`** | Convenient, and unsuitable for anything deployed. It permits drift between environments, cannot be reviewed, and cannot express data migrations. Flyway plus `validate` gives a reviewable, reproducible schema. |
| **NoSQL document store** | The data is highly relational with real integrity requirements, and the queue query is a join. A document store would push integrity into application code and lose the transaction boundary — directly undermining [ADR-001](ADR-001-modular-monolith.md)'s main benefit. |
| **Optimistic locking replaced by a database-level unique constraint** (e.g. on a decision row) | A reasonable alternative, but it would force a separate decision table purely as a locking device. The `@Version` column is simpler and the service already reads the status before deciding. |

## Follow-up

The initial migration (`V1__baseline.sql`) must create all foreign keys, `CHECK` constraints,
and indexes described in [data-model.md](../architecture/data-model.md). Integration tests must
prove that the constraints actually reject invalid writes — a constraint that is never tested
is a constraint that may not exist.

---

## Clarification — 2026-10-01: Spring Boot 4 moved Flyway's autoconfiguration

The first real `mvn clean verify` exposed a failure this ADR did not anticipate. The decision
above is unchanged; the way it is implemented had to change, and that belongs on the record.

**What happened.** The build compiled, packaged, and started the Spring context against a real
MySQL 8.4.11 container. **No migration ran.** Nothing failed — the context came up green and
the application reported healthy. The gap surfaced only because a test asserted the table
existed:

```
Table 'test.users' doesn't exist
```

**Why.** Spring Boot 4 split the monolithic `spring-boot-autoconfigure` into per-technology
modules. `FlywayAutoConfiguration` now lives in **`spring-boot-flyway`**, and declaring
`org.flywaydb:flyway-core` alone pulls in a library that nothing ever calls. Version 3.x
tolerated that; 4.x does not.

This is a worse failure mode than a crash. The application starts, the health check passes, and
the database is empty — so a deployment pipeline would report success against a broken system.
**It is precisely the class of defect that the health check cannot catch and that an
integration test can**, and in this case the integration test did.

**Resolution.** The POM now declares `org.springframework.boot:spring-boot-starter-flyway`
alongside `flyway-core` and `flyway-mysql`.

**Rule carried forward.** On Spring Boot 4, a third-party integration needs *both* its library
and its `spring-boot-<technology>` module. Adding only the library produces silence, not an
error — so any new integration is not done until something asserts its effect.
