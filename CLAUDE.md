# CLAUDE.md — Engineering Handbook

This file is the **rules of engagement** for this repository. It applies to human
contributors and to AI assistants alike. When a request conflicts with this document, follow
this document and say so, rather than silently diverging.

Read it before making any change. Update it when a rule changes.

---

## 1. Project purpose

Build a **Git-Driven Online Assignment Submission Portal**: a small but production-style
application in which students submit coursework as PDF files and reviewers approve or reject
them.

The application is intentionally modest. The **real deliverable is the delivery pipeline** —
a change must travel from a GitHub pull request, through Jenkins, Maven, unit, integration
and Selenium tests, a Docker image, a registry, Ansible provisioning, deployment, a health
check, and a rollback path — repeatably and with evidence.

Consequences of that purpose:

- A feature that cannot be tested, built, or deployed by the pipeline is not finished.
- Operational concerns (health checks, rollback, configuration) are first-class, not
  afterthoughts.
- Complexity in the application is a **cost** charged against the pipeline budget. Prefer the
  simplest thing that satisfies the requirement.

## 2. Architecture

**A modular monolith, not microservices.**

- One deployable unit: a Spring Boot executable JAR with embedded Tomcat.
- One relational system of record: MySQL.
- Server-rendered Thymeleaf UI — no SPA, no separate frontend build.
- Internal boundaries are expressed as **packages with a strict dependency direction**, not as
  network hops.
- Session-based authentication for a browser-oriented application.

Modularity is a discipline, not a deployment topology. Splitting the monolith is explicitly
not planned; if that ever changed, it would require an ADR superseding
[ADR-001](docs/adr/ADR-001-modular-monolith.md).

Full description: [`docs/architecture/system-overview.md`](docs/architecture/system-overview.md).

## 3. Stack

Fixed unless an existing repository constraint forces a justified change **recorded in an
ADR**.

| Concern | Technology |
| --- | --- |
| Language | Java 21 |
| Framework | Spring Boot 4.1.1 |
| Web | Spring MVC, server-rendered |
| View | Thymeleaf |
| Security | Spring Security (session-based, CSRF enabled) |
| Persistence | Spring Data JPA |
| Database | MySQL 8.4 |
| Migrations | Flyway |
| Build | Maven |
| Unit & integration tests | JUnit 5, Testcontainers |
| End-to-end tests | Selenium 4 |
| Containerisation | Docker, Docker Compose |
| CI | Jenkins |
| Provisioning | Ansible |

Do not add a dependency, framework, or language to this list casually. A new entry needs a
stated engineering objective and, if it changes an architectural decision, an ADR.

## 4. Package structure

**Package by feature, not by layer.** Base package:

```
com.vit.assignmentportal
```

```mermaid
flowchart TD
    App["AssignmentPortalApplication"]

    subgraph features [Feature packages — vertical slices]
        Auth["auth"]
        Submission["submission"]
        Review["review"]
    end

    subgraph capabilities [Shared capabilities — narrow, named responsibilities]
        Storage["storage"]
        Config["config"]
    end

    subgraph kernel [Shared kernel — value types only]
        Common["common"]
    end

    features --> capabilities
    features --> kernel
    capabilities --> kernel
```

Rules:

- Each feature package owns its entity, repository, service, controller, and templates. A
  feature is a **vertical slice**: `submission/` contains everything about submissions.
- Layers are **inside** the feature, not beside it. There is no top-level `controllers/`,
  `services/`, or `repositories/` package.
- Dependencies flow **inward and downward** only: features → capabilities → `common`. A
  feature never reaches into another feature's repository or entity; it calls that feature's
  service.
- A package that holds shared code must describe a **single concrete responsibility**
  (`storage`, `config`). See naming rules below.
- `common` is the **shared kernel** — value types, enums, and domain exceptions only. It is
  **not** a utilities bucket. The moment it holds a class with no single nameable
  responsibility, the package has failed and the class belongs in a feature instead.

Verified layout and dependency rules:
[`docs/architecture/application-structure.md`](docs/architecture/application-structure.md).

## 5. Naming rules

**Banned outright:** `Utils`, `Helper`, `CommonUtils`, `Misc`, `Manager`, `Processor`, and any
other name that describes a shape rather than a responsibility. A class named `Utils` becomes
a dumping ground; a class named `ChecksumService` cannot.

Reusable logic must be named for **what it does**:

| Name | Responsibility |
| --- | --- |
| `ChecksumService` | Computes and verifies SHA-256 digests of uploaded files |
| `FileValidationService` | Enforces PDF-only, ≤ 10 MB, and content checks |
| `StorageService` | Maps a storage identifier to a location and performs file I/O |
| `CurrentUserService` | Resolves the authenticated principal and its role |
| `AuditService` | Records who did what, to which entity, and when |

Other conventions:

- Classes: `PascalCase`. Methods and fields: `camelCase`. Constants: `UPPER_SNAKE_CASE`.
- Entities are singular nouns (`Submission`, not `SubmissionEntity`). Suffix only when it
  disambiguates: `SubmissionRepository`, `SubmissionService`, `SubmissionController`.
- DTOs are **Java records** where the value is immutable, and are named for their role
  (`SubmissionSummary`, `ReviewDecision`), not `SubmissionDto`.
- Test classes mirror the class under test and end in `Test` (unit) or `IT` (integration).
- Database tables and columns: `snake_case`, plural tables (`submissions`,
  `submission_status_history`).
- **Avoid Lombok.** Use records, explicit accessors, or plain fields. A Lombok dependency
  requires a concrete, documented reason and an ADR.

## 6. Testing philosophy

Test at the **lowest layer that can prove the behaviour**, and use the higher layers only for
what the lower ones cannot reach.

| Layer | Tool | Proves | Speed |
| --- | --- | --- | --- |
| Unit | JUnit 5, no Spring context | Business rules in isolation | Milliseconds |
| Integration | Spring Boot test slice + Testcontainers MySQL | Persistence, migrations, security wiring, real SQL | Seconds |
| End-to-end | Selenium 4 against a running deployment | A real user can complete a workflow through a browser | Minutes |

Principles:

- A test asserts **behaviour**, not implementation. If a refactor breaks a test without
  changing behaviour, the test was wrong.
- **Testcontainers, not H2**, for persistence tests: the MVP must be verified against the
  same engine that runs in production. Flyway migrations are exercised by the same tests.
- Cover the **security boundaries** explicitly: anonymous access is rejected, a STUDENT
  cannot read or review another student's submission, CSRF is enforced on state-changing
  requests, and role checks hold.
- Upload handling is tested with hostile input: oversized files, non-PDF content, a PDF
  extension on non-PDF bytes, and filenames containing path-traversal sequences.
- Every bug fixed earns a regression test that fails before the fix.
- The pyramid must stay a pyramid. A stage that can be proven by a unit test does not belong
  in the Selenium suite.

Full strategy: [`docs/testing/strategy.md`](docs/testing/strategy.md).

## 7. Security rules

These are **non-negotiable**. A change that violates one is rejected regardless of how well
it works.

1. **Never hard-code a secret.** No password, token, private key, database password, Docker
   Registry credential, or deployment credential in any file — including tests, CI
   definitions, Ansible variables, and Compose files.
2. **Configuration is externalised.** Values arrive through environment variables or a
   managed secret store, injected at deploy time.
3. **CSRF protection stays enabled.** Every state-changing form submits a token. Disabling it
   requires an ADR containing a threat analysis.
4. **Uploads: PDF only, maximum 10 MB, validated server-side.** Client-side checks are a
   convenience and never a control.
5. **Never trust the original filename as a filesystem path.** Generate a server-side storage
   identifier for every upload and use only that to locate the file on disk.
6. **Record a SHA-256 checksum** for every stored file.
7. Store files on a **persistent volume**; store **metadata** in MySQL. The container
   filesystem is ephemeral and must never be the only copy of an artefact.
8. **Authorise server-side on every request**, from the authenticated principal and its role.
   Never from a client-supplied identifier.
9. **Roles are exactly `STUDENT`, `REVIEWER`, `ADMIN`.** A role is never inferred from a
   request parameter.
10. Serve uploaded files as attachments with a fixed content type — never inline-rendered.
11. User-facing errors never expose stack traces, SQL, filesystem paths, or configuration.

Full policy and threat model: [`SECURITY.md`](SECURITY.md).

## 8. Configuration and secrets

- All configuration is **externalised and environment-driven**; the same artefact is promoted
  from environment to environment without being rebuilt.
- Database URL, credentials, storage root, and registry details come from the environment.
- Precedence, lowest to highest: `application.yml` (defaults, safe to commit) →
  `application-<profile>.yml` (non-secret profile values) → environment variables
  (all secrets).
- `.env.example` documents **variable names only** and is committed. `.env` holds real values
  and is git-ignored.
- Secrets in CI live in the Jenkins credentials store. Secrets at deploy time live in Ansible
  Vault or the target host environment.
- Never log a secret, a full connection string, or a session identifier.

## 9. Git workflow

```
GitHub → Pull Request / Review → Jenkins CI → Maven Build → Unit Tests
       → Integration Tests → Selenium E2E → Docker Image → Docker Registry
       → Ansible Provisioning → Deployment → Health Check → Rollback
```

- `main` is protected. **No direct pushes.** Every change arrives via pull request.
- Branch names use a type prefix: `feature/`, `fix/`, `docs/`, `chore/`, `refactor/`.
- Commits follow **Conventional Commits**: `<type>(<scope>): <imperative summary>`, with a
  body explaining **why**.
- A PR states the problem, the approach, and the verification actually performed. It fills in
  `.github/pull_request_template.md` completely and honestly.
- CI must be green, including Selenium, before merge. A red pipeline is never bypassed.
- Changing an architectural decision means **adding or superseding an ADR**, never silently
  rewriting the record.
- Documentation moves with code in the same PR.

Detail: [`CONTRIBUTING.md`](CONTRIBUTING.md).

## 10. Expected verification commands

The canonical commands for this repository. The **Maven Wrapper is the canonical build
mechanism** — `mvn` on `PATH` is not required, and the wrapper pins the Maven version so
every machine and every CI agent builds with the same one.

```bash
# Full local verification — compile, unit tests, integration tests
./mvnw clean verify

# Unit tests only (fast feedback)
./mvnw test

# Integration tests only — requires a running Docker daemon for Testcontainers
./mvnw failsafe:integration-test

# End-to-end tests (requires a running deployment)
./mvnw verify -Pe2e

# Start the local dependencies
docker compose -f deploy/docker-compose.yml up -d

# Run the application locally (reads .env / environment variables)
./mvnw spring-boot:run
```

On Windows PowerShell or `cmd.exe`, use `mvnw.cmd`:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd spring-boot:run
```

`clean verify` requires Docker to be running: the integration test starts a MySQL 8.4
container (docs/testing/strategy.md §3.2).

> **Do not claim a command was run unless it was.** Report the real outcome, including
> failures, and paste the relevant output.

## 11. Current project status

**Milestone 4 — the reviewer decision, the status history, and file download are implemented. No
test suite later than Milestone 2's has been executed, so Milestones 3 and 4 are written but
unproven.**

The last recorded `mvn clean verify` (2026-10-01T12:37) was **green**: 30 Failsafe tests and 5
Surefire tests, zero failures. That run covered Milestone 2's code and predates every Milestone 3
and 4 file described below, so it is evidence for the two milestones behind us and for nothing in
the two in front of us. The toolchain it used is recorded in
[`docs/operations/toolchain.md`](docs/operations/toolchain.md), whose §9 records the same gap.

Milestone 1 established the build and runtime. Milestone 2 added database-backed authentication.
Milestone 3 added the first feature that accepts user-supplied content. Milestone 4 closes the
workflow loop: a submission can now be decided, the decision is recorded, and the student can read
it — which is also the first milestone whose tests exercise a state transition end to end.


### What exists

- Root documents: `README.md`, `CLAUDE.md`, `CONTRIBUTING.md`, `SECURITY.md`, `.editorconfig`,
  `.gitattributes`, `.gitignore`, `.env.example`, `cspell.json`.
- `docs/requirements/mvp.md` — scope and explicit exclusions.
- `docs/architecture/` — system overview, application structure, data model, security.
- `docs/adr/` — ADR-001 through ADR-007.
- `docs/testing/strategy.md`, `docs/operations/toolchain.md`, `docs/evidence/`.
- `.github/` issue templates and pull request template.
- `pom.xml` — Java 21, Spring Boot 4.1.1, pinned dependency set, enforcer rules.
- **The Maven Wrapper**, pinned to Maven 3.9.16 with `distributionType=only-script`. `./mvnw` is
  the canonical build command of §10 and needs nothing on `PATH`.
- `deploy/docker-compose.yml` — local MySQL 8.4 with a named volume and healthcheck.
- `src/main/resources/application.yml` — externalised configuration, no secrets.

**`auth/` — identity (Milestone 2).** `User` entity and repository, `UserService`, and a
session-based Spring Security configuration with CSRF enabled: form login, logout that invalidates
the session server-side, `STUDENT`/`REVIEWER`/`ADMIN` mapped to authorities, and deny-by-default
route rules. `DevelopmentUserSeeder` creates demo accounts under the `dev` profile only, and only
when `APP_SEED_ENABLED` is true; each password comes from its own environment variable, and none
has a default. Documented in [`docs/architecture/security.md`](docs/architecture/security.md).

**`storage/` — file bytes on a volume (Milestone 3).** `StorageService`, `StorageIdentifier`,
`StorageProperties`, `StorageException`, `FileValidationService`, `ChecksumService`. PDF-only,
≤ 10 MB, validated by content and not by name; a server-generated identifier that makes path
traversal unrepresentable; SHA-256 recorded for every stored file. Bytes are written outside any
servable path. `StorageService.load` was added in Milestone 4, when the download route gave it a
caller.

**`submission/` — the student's slice (Milestone 3), and the transition it owns (Milestone 4).**
`Submission` entity and repository, `SubmissionService`, `SubmissionController`, the read models,
and three templates. Student routes: `GET /submissions/new`, `POST /submissions`,
`GET /submissions`, `GET /submissions/{id}`, `GET /submissions/{id}/file`. A student reaches only
their own work — the ownership test is a `WHERE` clause, so somebody else's submission is never
loaded rather than loaded and refused. The file and the row cannot be committed together, so
`SubmissionService` orders the writes and removes the file if the row does not commit.

Milestone 4 added `SubmissionStatusHistory` — an append-only entity and repository — and
`SubmissionService.recordDecision`, which applies a status change and writes its history row in one
transaction. The terminality rule lives on the entity (`Submission.approve` / `reject` throw
`SubmissionAlreadyDecidedException` for a row that is not `SUBMITTED`), so a second approval, a
second rejection, and a decision on already-decided work are all refused at the same place.
`Submission.version` is a `@Version` column, so two reviewers deciding concurrently cannot silently
overwrite one another.

**`review/` — the reviewer's slice (Milestone 4).** `ReviewService`, `ReviewController`,
`ReviewDecision`, `InvalidReviewException`, and two templates. Routes: `GET /review` (the queue of
`SUBMITTED` work), `GET /review/submissions/{id}`, `POST /review/submissions/{id}` (decide),
`GET /review/submissions/{id}/file`. The decision *policy* lives here — a rejection requires a
reason, an approval must not carry one, and the outcome must be `APPROVED` or `REJECTED`. The
*transition* does not: `ReviewService` calls `SubmissionService`, because `review` may not import a
submission repository or mutate a `Submission` (`application-structure.md` §4 rule 1). Reviewers
have no route that accepts a file; the artefact is read-only to them.

**Migrations.** `V1__baseline.sql` creates `users`; `V2__submissions.sql` creates `submissions`
with its `CHECK` constraints, unique stored-name index, and foreign keys;
`V3__submission_status_history.sql` creates the append-only history table. `ApplicationContextIT`
exercises each constraint by inserting rows the service would never produce.

### Not yet present — deliberately

- **No audit trail.** ADR-005 requires sign-in attempts, including failures, to be recorded. This
  is the one Milestone 2 entry criterion still unmet, and the one Milestone 4 entry criterion still
  unmet; `docs/architecture/security.md` §12 records why, and `application-structure.md` §5 records
  the unresolved question about where such a package would live. `audit_events` does not exist.
  Note the distinction: `submission_status_history` now records *workflow* transitions, which is a
  different concern from *security* events.
- **No resubmission.** A rejection is terminal in the MVP. Rejected work is not resubmitted, and
  nothing resets a submission to `SUBMITTED`.
- **No `Dockerfile`, no `Jenkinsfile`, no Ansible playbooks, no pipeline stages.** The real
  deliverable of this project — the pipeline — is still ahead.
- **No admin UI.** `ADMIN` exists as a role, gates `/admin/**`, and can review; no management
  screens ship.

**Do not add placeholder Java classes merely to fill directories.** Empty structure with no
behaviour is worse than no structure: it implies work that has not happened. Create a class
when it has a responsibility.

## 12. Mandatory Claude response format

When reporting work in this repository, respond using **exactly** these sections, in this
order:

1. **Implementation Summary**
2. **Changed Files**
3. **File-by-File Learning Flow**
4. **Verification Performed**
5. **Decisions and Assumptions**
6. **Remaining Risks / Limitations**
7. **Next Milestone Readiness**

Rules for that response:

- **Changed Files** lists every file created, modified, or deleted, with a one-line purpose.
- **File-by-File Learning Flow** gives the recommended order in which a person should open the
  changed files to understand the work, and why that order.
- **Verification Performed** states what was actually checked and what was **not**. Never
  imply a command ran when it did not.
- **Remaining Risks / Limitations** is honest. "None" is almost always the wrong answer.

## 13. Next milestone readiness

**Milestone 4 is code-complete and unproven.** Everything below is written; nothing below has been
executed. A `[x]` here means *implemented*, never *verified* — the one criterion that would turn
the first into the second is the last one, and it is open.

- [x] A `ReviewService` in `review/` recording one terminal decision — `APPROVED` or `REJECTED` —
      with a required comment (FR-18, FR-19), reachable only by a `REVIEWER` or an `ADMIN`.
- [x] A review queue of submissions in `SUBMITTED` (`GET /review`), and a reviewer's view of an
      artefact (FR-16, FR-17). Reaching submission data goes through `SubmissionService`; `review`
      imports no `SubmissionRepository` and mutates no `Submission` directly
      ([`application-structure.md` §4](docs/architecture/application-structure.md)).
- [x] The **terminality rule** enforced on the entity and re-asserted by the service, not in the
      view: a decision is final and a decided submission cannot be decided again (FR-20). Rejected
      work is **not** resubmitted — that is explicitly out of MVP scope.
- [x] `reviewed_at` and `reviewer_id` written in the same statement as the status, so
      `ck_submissions_decision` can never fire. It exists to catch a bug; the entity is written so
      it cannot be the source of one.
- [x] `SubmissionStatusHistory` — entity, table (`V3__submission_status_history.sql`), and one
      append-only row per transition, carrying previous status, new status, actor, timestamp and
      note (FR-15, FR-21). `SubmissionService.submit` writes the creation row and
      `recordDecision` writes the decision row, each in the same transaction as the status it
      describes.
- [x] Optimistic locking on `Submission.version`, so two reviewers deciding at once cannot silently
      overwrite one another; the loser is told their decision was not recorded.
- [x] File download for the owner (FR-13) and the reviewer (FR-17), served as an attachment with a
      fixed content type and `nosniff` (`CLAUDE.md` §7 rule 10).
- [x] The student sees the decision and the reviewer's reason on their own submission, together
      with a chronological history — and still cannot see anyone else's (FR-14).
- [x] Tests written for the whole workflow: approve, reject, a missing reason, an invalid
      transition, a second decision, a concurrent conflict, history creation, reviewer
      authorisation, a student refused at every review route, and a student confined to their own
      data. **Written, not run.**
- [ ] **The audit trail, still outstanding from Milestone 2** (FR-24). ADR-005 requires sign-in
      attempts — **including failures** — to be recorded. No `audit_events` table and no
      `AuditService` exist, and the package that would own one is an open question recorded in
      [`application-structure.md` §5](docs/architecture/application-structure.md).
- [ ] **`./mvnw clean verify` green, and the run recorded under
      [`docs/evidence/`](docs/evidence/).** This is the criterion that matters most and the only
      one still open. `docs/evidence/` holds no captured output for any milestone, and
      [`docs/operations/toolchain.md` §9](docs/operations/toolchain.md) records the same gap.

Nothing decided so far may be merged with a hard-coded secret. The version numbers recorded in
[`docs/operations/toolchain.md`](docs/operations/toolchain.md) must be updated with the
versions actually used — which cannot happen until a build runs.

**Milestone 5 — the delivery pipeline.** The application is now as complete as the MVP needs it to
be; the deliverable this project actually exists to demonstrate is still absent. Milestone 5
carries: a multi-stage `Dockerfile`; `Jenkinsfile` stages for build, unit, integration and Selenium
E2E tests, image publication to a registry, Ansible provisioning and deployment, a health check,
and a rollback path; the Selenium suite itself; and — if it is to be claimed — the FR-24 audit
trail carried over above. Nothing here should be started before the Milestone 4 build is green,
because a pipeline that has never seen a passing build has nothing to demonstrate.
