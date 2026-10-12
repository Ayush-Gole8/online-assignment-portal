# Task 02 — Architecture and decision records

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 02: Architecture and decision records |
| Objective | Explain the package-by-feature modular monolith, dependency direction, runtime components, and decisions that keep the solution understandable for one team. |
| Implementation | A single Spring Boot executable JAR contains auth, submission, review, storage, configuration, and common packages. Architecture and ADR documents explain why the project does not begin as microservices. |
| Primary files | [docs/architecture/system-overview.md](../../docs/architecture/system-overview.md), [docs/architecture/application-structure.md](../../docs/architecture/application-structure.md), [docs/architecture/end-to-end-flow.md](../../docs/architecture/end-to-end-flow.md), [docs/adr/ADR-001-modular-monolith.md](../../docs/adr/ADR-001-modular-monolith.md), [docs/adr/ADR-007-base-package-and-package-set.md](../../docs/adr/ADR-007-base-package-and-package-set.md) |
| Command/demo | Present the package tree and architecture sequence; use rg --files src/main/java/com/vit/assignmentportal to show the actual packages. |
| Expected result | The source tree and diagrams show one deployable with feature boundaries and a clear direction of dependencies. |

## Screenshot / evidence to capture

PENDING_CAPTURE

Capture the architecture diagram and package tree in one readable image or PDF, with the commit SHA and capture date.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

