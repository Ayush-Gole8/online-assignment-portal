# MVP Requirements

**Status:** Baselined — Milestone 0
**Scope owner:** Repository maintainer
**Last reviewed:** 2026-10-01

This document defines the **exact** functional scope of the MVP. Anything not listed as in
scope is out of scope by default. Items in §3 are excluded **deliberately**, not by omission,
and must not be implemented without a documented change (see §5).

---

## 1. Actors

| Role | Description |
| --- | --- |
| `STUDENT` | Submits assignment work as a PDF and tracks its outcome. |
| `REVIEWER` | Reviews submitted work and records an approve/reject decision. |
| `ADMIN` | Manages users and roles; can inspect the full audit trail. |

Roles are exactly these three. A user holds one role in the MVP.

## 2. Functional scope

### 2.1 Authentication and accounts

| ID | Requirement |
| --- | --- |
| FR-1 | A user can sign in with a username and password. Authentication is session-based. |
| FR-2 | A user can sign out; the session is invalidated server-side. |
| FR-3 | An `ADMIN` can create a user and assign exactly one role. |
| FR-4 | Passwords are stored only as a strong, salted, adaptive hash (BCrypt). Plaintext is never persisted or logged. |
| FR-5 | Unauthenticated requests to any non-public route are redirected to the sign-in page. |
| FR-6 | The sign-in form is protected against CSRF, and the session identifier is rotated on successful authentication. |

### 2.2 Student submission

| ID | Requirement |
| --- | --- |
| FR-7 | A `STUDENT` can upload an assignment as a **PDF**, maximum **10 MB**. |
| FR-8 | Upload validation happens **server-side**: content type, file extension, declared size, and content sniffing. |
| FR-9 | The original filename is retained as display metadata only and is **never** used as a filesystem path. |
| FR-10 | Every stored file is given a server-generated storage identifier and a recorded **SHA-256** checksum. |
| FR-11 | File bytes are written to a persistent volume; metadata is written to MySQL. |
| FR-12 | A `STUDENT` can view a list of their own submissions with current status and submission timestamp. |
| FR-13 | A `STUDENT` can download their own submitted file. |
| FR-14 | A `STUDENT` cannot see, download, or infer the existence of another student's submission. |
| FR-15 | A newly uploaded submission enters status `SUBMITTED` and records a status-history entry. |

### 2.3 Reviewer decision

| ID | Requirement |
| --- | --- |
| FR-16 | A `REVIEWER` can view the queue of submissions in status `SUBMITTED`. |
| FR-17 | A `REVIEWER` can open a submission and download its file, in order to review it. |
| FR-18 | A `REVIEWER` can record exactly one terminal decision: `APPROVED` or `REJECTED`. |
| FR-19 | A decision requires a comment, stored with the decision. |
| FR-20 | A decision is final. A submission in a terminal status cannot be decided again. |
| FR-21 | Every transition writes a `SubmissionStatusHistory` row: previous status, new status, actor, timestamp, comment. |

### 2.4 Audit and administration

| ID | Requirement |
| --- | --- |
| FR-22 | An `ADMIN` can view the status history of any submission. |
| FR-23 | An `ADMIN` can list users and their roles. |
| FR-24 | Security-relevant events (sign-in success, sign-in failure, submission created, decision recorded) are recorded in an audit trail. |

### 2.5 Operability (required by the pipeline, not optional)

| ID | Requirement |
| --- | --- |
| FR-25 | The application exposes a health endpoint that reports readiness without authentication and without leaking configuration. |
| FR-26 | All environment-specific configuration (database URL, credentials, storage root) is supplied through environment variables. |
| FR-27 | Schema changes are applied exclusively through versioned Flyway migrations — never through Hibernate auto-DDL. |
| FR-28 | The application starts against a reachable, migrated MySQL 8.4 and refuses to start against an unreachable database rather than degrading silently. |

### 2.6 State model

```
                 ┌──────────────────────────────┐
                 │                              │
   (upload)      ▼          approve             │
  ─────────▶ SUBMITTED ──────────────▶ APPROVED │  terminal
                 │                              │
                 │          reject              │
                 └─────────────▶ REJECTED ──────┘  terminal
```

- `SUBMITTED` is the only non-terminal state.
- `APPROVED` and `REJECTED` are terminal. No transition leaves a terminal state.

## 3. Explicitly out of scope

These items are **deliberately excluded from the MVP**. Each is excluded for a stated reason;
none is excluded because it was forgotten.

| # | Excluded | Reason |
| --- | --- | --- |
| 1 | **Resubmission after rejection** | Rejected is terminal in the MVP. Allowing resubmission changes the state model, the audit semantics, and the reviewer queue. It is a documented future change, not an omission. |
| 2 | **Assignment creation / deadline management** | Assignments are a fixed, seeded catalogue. Building a CRUD module for them adds surface area without exercising the pipeline. |
| 3 | **Plagiarism or similarity detection** | A separate non-trivial subsystem (text extraction, embeddings, comparison). Out of the assignment's engineering objective. |
| 4 | **File formats other than PDF** | The single-format constraint is what makes server-side validation tractable and testable. |
| 5 | **Files larger than 10 MB, chunked or resumable upload** | Requires streaming, resumable protocols, and different storage semantics. |
| 6 | **Email / notification delivery** | Adds an external dependency and credentials to every environment for no pipeline benefit. |
| 7 | **Password self-service reset** | Requires an email channel (see #6). An `ADMIN` resets passwords in the MVP. |
| 8 | **Multi-tenancy, courses, semesters, cohorts** | An organisational model the MVP does not need. |
| 9 | **A REST/JSON API** | The application is server-rendered. A REST surface would be a second, unexercised interface. See [`ADR-002`](../adr/ADR-002-spring-boot-embedded-tomcat.md). |
| 10 | **Single-page frontend, JavaScript build pipeline** | Disproportionate toolchain cost for a server-rendered application. |
| 11 | **Microservices, message brokers, event streaming** | Explicitly rejected in [`ADR-001`](../adr/ADR-001-modular-monolith.md). |
| 12 | **Kubernetes** | The target is Compose-based composition provisioned by Ansible. |
| 13 | **Horizontal scaling, caching layers, CDN** | A single instance behind a health check is the deployment model. |
| 14 | **Soft delete / recycle bin** | Deletion is not a user-facing MVP operation. |
| 15 | **Localisation / internationalisation** | English only. |
| 16 | **Accessibility audit to WCAG conformance** | Semantic markup and keyboard operability are expected; formal conformance is out of scope. |
| 17 | **Lombok, MapStruct, or other code-generation dependencies** | See [`CLAUDE.md` §5](../../CLAUDE.md). |
| 18 | **Mobile applications and push notifications** | Not part of the browser-oriented product. |

## 4. Non-functional requirements

| ID | Requirement |
| --- | --- |
| NFR-1 | The application runs on Java 21 against MySQL 8.4 and is packaged as a Spring Boot executable JAR. |
| NFR-2 | A browser user can complete the full submit → review workflow with JavaScript disabled. |
| NFR-3 | The same build artefact is promoted across environments; nothing is rebuilt per environment. |
| NFR-4 | A deployment is reversible: the previous image can be restored and the health check re-run. |
| NFR-5 | Uploaded files survive a container restart or replacement. |
| NFR-6 | Unit tests dominate the suite; integration tests prove persistence and security; Selenium proves the user workflows end to end. |
| NFR-7 | No secret exists in the repository or in any build artefact. |

## 5. Changing this scope

Adding an item from §3, or any behaviour not listed in §2, requires:

1. A pull request that states the engineering objective the addition serves.
2. An update to this document **in the same PR**, moving the item out of §3 with a rationale.
3. An ADR if the change alters an architectural decision.

Verbal agreement in a review comment is not sufficient.
