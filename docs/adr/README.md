# Architecture Decision Records

An ADR records a **decision**, the **context** that forced it, and the **consequences** that
were accepted. It is not a design document and not a tutorial: it exists so that a reader six
months from now can understand *why* the system is shaped this way, and what it cost.

## Why this directory exists

Adding or changing an architectural decision without recording it means the reasoning lives
only in a pull request thread or someone's memory. Both are lost. An ADR makes the reasoning
durable and reviewable. In this repository, **changing an architectural decision means adding
or superseding an ADR** — never silently rewriting the record
([`CLAUDE.md` §9](../../CLAUDE.md)).

## Index

| ADR | Decision | Status |
| --- | --- | --- |
| [ADR-001](ADR-001-modular-monolith.md) | Modular monolith architecture, not microservices | Accepted |
| [ADR-002](ADR-002-spring-boot-embedded-tomcat.md) | Spring Boot executable JAR with embedded Tomcat as the deployable unit | Accepted |
| [ADR-003](ADR-003-mysql-system-of-record.md) | MySQL 8.4 as the system of record, with Flyway and `validate` | Accepted |
| [ADR-004](ADR-004-filesystem-volume-and-db-metadata.md) | Filesystem volume for file bytes, database for metadata | Accepted |
| [ADR-005](ADR-005-session-authentication.md) | Session-based authentication with Spring Security | Accepted |
| [ADR-006](ADR-006-docker-compose-composition.md) | Docker Compose for local and deployment composition | Accepted |
| [ADR-007](ADR-007-base-package-and-package-set.md) | Base package `com.vit.assignmentportal` and the initial package set | Accepted |

## Status values

| Status | Meaning |
| --- | --- |
| **Proposed** | Under discussion. Not yet binding. |
| **Accepted** | Binding. The system must conform. |
| **Deprecated** | Still in effect but no longer recommended; a successor exists. |
| **Superseded by ADR-NNN** | Replaced. Kept for history — **never deleted**. |

An accepted ADR is **never edited to say something different**. If the decision changes, write
a new ADR and mark the old one superseded, linking both. The point of the record is that the
history of reasoning is visible.

## Template

```markdown
# ADR-NNN — <short decision title>

- **Status:** Proposed | Accepted | Deprecated | Superseded by ADR-NNN
- **Date:** YYYY-MM-DD
- **Deciders:** <who>
- **Related:** <links to other ADRs and documents>

## Context
What is the situation, and what force makes a decision necessary? Describe the constraints and
the requirements — not the solution. A reader should be able to see why *some* decision was
unavoidable.

## Decision
What was decided, stated in the active voice: "We will ...". Be specific enough that a
reviewer can check conformance.

## Consequences
What becomes easier, and what becomes harder. **Include the negative consequences.** An ADR
listing only benefits is marketing, not engineering. Every real decision has a cost; naming it
is what makes the record trustworthy.

## Alternatives considered
The realistic options and the specific reason each was rejected. "It seemed worse" is not a
reason. This section is the most valuable one for a future reader.

## Follow-up
Any obligation this decision creates — a test that must exist, a document that must be
written, a limitation to revisit.
```

## Conventions

- One decision per ADR. If two decisions are entangled, they overlap and both need rewriting.
- Number sequentially, three digits: `ADR-007-...`. **Never renumber.**
- File name: `ADR-NNN-kebab-case-title.md`.
- Written in the past tense of deliberation — the decision was made at a point in time, and the
  record preserves that point of view.
