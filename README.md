# Online Assignment Portal

A **Git-Driven Online Assignment Submission Portal** built as a Computer Engineering capstone
project. Students submit coursework as PDF files; reviewers approve or reject them. The
application is intentionally modest -- the **real deliverable is the delivery pipeline**: a change
travels from a GitHub pull request, through Jenkins, Maven tests, a Docker image, a registry,
Ansible provisioning, deployment, health-check, and a rollback path, repeatably and with evidence.

---

## Table of Contents

1. [What the project is](#1-what-the-project-is)
2. [Why it exists](#2-why-it-exists)
3. [Architecture](#3-architecture)
4. [How to run locally](#4-how-to-run-locally)
5. [How to run tests](#5-how-to-run-tests)
6. [How to build the Docker image](#6-how-to-build-the-docker-image)
7. [How deployment works](#7-how-deployment-works)
8. [How Ansible works](#8-how-ansible-works)
9. [How rollback works](#9-how-rollback-works)
10. [Where configuration comes from](#10-where-configuration-comes-from)
11. [What is intentionally out of scope](#11-what-is-intentionally-out-of-scope)
12. [Repository map](#12-repository-map)

---

## 1. What the project is

A server-rendered web application where:

- A **STUDENT** signs in, uploads a PDF assignment (10 MB maximum), and tracks its status.
- A **REVIEWER** opens the submission queue, reads the PDF, and records one terminal decision --
  `APPROVED` or `REJECTED` -- with a mandatory comment.
- Every status transition is recorded in an append-only history table.
- An **ADMIN** role exists for user management (no admin UI ships in the MVP).

The application is a **modular monolith**: one deployable JAR, one database, no microservices.
Complexity is a cost: the simpler the application, the more of the project budget goes to the
pipeline.

**Functional requirements:** [`docs/requirements/mvp.md`](docs/requirements/mvp.md)

---

## 2. Why it exists

The project demonstrates a complete, production-style delivery pipeline for a Computer Engineering
capstone. The criterion for success is not feature count -- it is the ability to:

1. Take a code change from a pull request to a deployed, verified release.
2. Detect a bad deployment automatically and roll it back.
3. Provision a bare host reproducibly with Ansible.
4. Prove the above with captured evidence, not assertion.

The application is the vehicle. The pipeline is the point.

---

## 3. Architecture

### Runtime shape

```
Browser  --HTTP by default; TLS terminator required externally-->  Spring Boot (embedded Tomcat, port 8080)
                         |
                         +-- Spring Security (session auth, CSRF, role checks)
                         +-- Spring MVC + Thymeleaf (server-rendered pages)
                         +-- Feature packages: auth / submission / review
                         +-- Shared capabilities: storage / config
                         +-- Spring Data JPA --JDBC-->  MySQL 8.4
                                                           |
                         StorageService --file I/O-->  Persistent volume (PDF bytes)
```

### Package structure (feature-first, not layer-first)

```
com.vit.assignmentportal
+-- auth/           - User entity, DatabaseUserDetailsService, CurrentUserService
+-- submission/     - Submission entity, SubmissionService, SubmissionController
|                     SubmissionStatusHistory (append-only, one row per transition)
+-- review/         - ReviewService, ReviewController, ReviewDecision
+-- storage/        - StorageService, StorageIdentifier, ChecksumService,
|                     FileValidationService
+-- config/         - SecurityConfiguration, StorageProperties
+-- common/         - shared value types, domain exceptions
```

Dependency direction: features depend on capabilities depend on common. A feature never imports
another feature's repository or entity directly.

### Delivery pipeline

```
GitHub PR  --review-->  Jenkins CI
                          +-- Build (compile both source sets)
                          +-- Unit Tests (JUnit 5, Surefire, no Spring context)
                          +-- Integration Tests (Failsafe + Testcontainers MySQL 8.4)
                          +-- Package (Spring Boot executable JAR)
                          +-- Start Test Environment (db + JAR + health wait)
                          +-- Selenium E2E (real browser, real deployment)
                          +-- Docker Build (multi-stage image, tagged by commit/version)
                          +-- Publish Image (Docker Hub, vX.Y.Z tag only)
                          +-- Deploy (SSH to host, compose up, health check, rollback on failure)
```

**Detailed documents:**

| Concern | Document |
| --- | --- |
| System context and request lifecycle | [`docs/architecture/system-overview.md`](docs/architecture/system-overview.md) |
| Package layout and dependency rules | [`docs/architecture/application-structure.md`](docs/architecture/application-structure.md) |
| End-to-end request flow | [`docs/architecture/end-to-end-flow.md`](docs/architecture/end-to-end-flow.md) |
| Data model | [`docs/architecture/data-model.md`](docs/architecture/data-model.md) |
| Security model | [`docs/architecture/security.md`](docs/architecture/security.md) |
| Architecture Decision Records | [`docs/adr/`](docs/adr/) |

---

## 4. How to run locally

### Prerequisites

| Tool | Version | Notes |
| --- | --- | --- |
| JDK | 21 (exactly) | Set `JAVA_HOME`; Maven Wrapper enforces the version |
| Docker Desktop | current | Required for integration tests and full stack |
| Git | any | For checkout |

The Maven Wrapper (`mvnw` / `mvnw.cmd`) is the **canonical build tool**. It pins Maven 3.9.16.
Do not use a system Maven -- the wrapper is what CI uses, and divergence from CI is the failure.

### Steps

```bash
# 1. Clone the repository
git clone https://github.com/<org>/online-assignment-portal.git
cd online-assignment-portal

# 2. Copy and populate the environment file
cp .env.example .env
# Edit .env: set DB_PASSWORD, DB_ROOT_PASSWORD, APP_SEED_STUDENT_PASSWORD,
#            APP_SEED_REVIEWER_PASSWORD (STORAGE_ROOT defaults are usable for dev)

# 3. Start the database
docker compose up -d db

# 4. Run the application (reads .env automatically)
./mvnw spring-boot:run          # Linux / macOS / Git Bash
.\mvnw.cmd spring-boot:run      # Windows PowerShell / cmd.exe

# 5. Open http://localhost:8080
#    Demo accounts (APP_SEED_ENABLED=true is the default for the dev profile):
#      student  / <APP_SEED_STUDENT_PASSWORD>
#      reviewer / <APP_SEED_REVIEWER_PASSWORD>
```

To run the full stack as Docker containers (application + database, no local Java needed):

```bash
./mvnw clean package -DskipTests
docker compose up -d --build
# Application: http://localhost:8080
# Logs:        docker compose logs -f app
# Stop:        docker compose down        (preserves database and uploads)
# Destroy:     docker compose down -v     (deletes database and uploads)
```

---

## 5. How to run tests

The test suite is partitioned into three layers. The Maven Wrapper commands are canonical:

```bash
# Full verification -- compile, unit tests, integration tests (requires Docker)
./mvnw clean verify
.\mvnw.cmd clean verify          # Windows

# Unit tests only (fast, no Docker needed)
./mvnw test

# Integration tests only (requires Docker daemon)
./mvnw failsafe:integration-test failsafe:verify

# Selenium E2E (requires a running application -- start it first)
./mvnw verify -Pe2e

# Single test class, integration example
.\mvnw.cmd -B -Dit.test=ReviewWorkflowIT verify
```

| Layer | Mechanism | Tag | Speed |
| --- | --- | --- | --- |
| Unit | Surefire, `*Test.java` | `unit` | Milliseconds |
| Integration | Failsafe, `*IT.java`, Testcontainers | `integration` | Seconds |
| E2E | Failsafe, `*E2E.java`, Selenium | `e2e` | Minutes |

`clean verify` never runs E2E -- it requires the `-Pe2e` profile, which requires a human or CI
stage to start the environment first.

**Testing strategy:** [`docs/testing/strategy.md`](docs/testing/strategy.md)

---

## 6. How to build the Docker image

The Dockerfile does **not** run Maven. It packages the JAR that `./mvnw package` already
produced, so the tested artefact and the deployed artefact are the same bytes.

```bash
# 1. Produce the JAR
./mvnw clean package -DskipTests

# 2. Build the image (local tag)
docker build -t online-assignment-portal:local .

# 3. Build with a version baked in (as the pipeline does)
docker build --build-arg APP_VERSION=1.0.0 -t online-assignment-portal:1.0.0 .

# 4. Inspect the result
docker image inspect online-assignment-portal:local
```

The image is a **two-stage build**:

1. **Unpack stage** -- extracts the Spring Boot JAR into four layers (dependencies,
   spring-boot-loader, snapshot-dependencies, application) so that rebuilds after a code change
   only rewrite the `application/` layer.
2. **Runtime stage** -- Eclipse Temurin 21 JRE, non-root user `portal` (UID/GID 1001),
   `curl`-based HEALTHCHECK, `STOPSIGNAL SIGTERM` for graceful drain, `APP_VERSION` baked in.

**Full image lifecycle:** [`docs/operations/docker.md`](docs/operations/docker.md)

---

## 7. How deployment works

A release is triggered by pushing a `vX.Y.Z` git tag. Jenkins:

1. **Identifies the version** from the tag (`scripts/ops/release-version.cmd`).
2. **Builds and tags** the image `<repository>:<version>`.
3. **Publishes** the image to Docker Hub (credentials from the Jenkins store, never in code).
4. **Connects** to the deployment host over SSH (`sshagent`).
5. **Runs the pre-release gate** -- verifies persistent volumes, takes a MySQL dump, writes a
   release context file.
6. **Runs `deploy-on-host.sh`** -- pulls the exact tagged image, runs `docker compose up
   --no-build --wait`, waits for the health endpoint.
7. **Runs the smoke test** -- verifies `/actuator/health` is UP and `/actuator/info` reports the
   exact deployed version.
8. **On success**, appends a `success` row to `releases/ledger.tsv`.
9. **On failure**, triggers rollback automatically (same command as the manual rollback).

Only a green build of a `vX.Y.Z` tag reaches the Publish and Deploy stages. A feature branch
builds and tests but never publishes.

**Release runbook:** [`docs/operations/release-runbook.md`](docs/operations/release-runbook.md)
**CD pipeline detail:** [`docs/operations/docker-cd.md`](docs/operations/docker-cd.md)

> **Status:** The release pipeline is written. No image has been built by this pipeline, no push
> attempted, and no deployment host contacted. See `docker-cd.md` Section 8 for the full list of
> unexercised claims.

---

## 8. How Ansible works

Ansible provisions a bare Linux host and keeps it converged. Three roles run in order:

| Role | Responsibility |
| --- | --- |
| `common` | Base packages (curl, git, jq), time sync (chrony). Generic -- knows nothing about the portal. |
| `docker` | Docker Engine, Compose v2 plugin, Python Docker SDK, `daemon.json` (log limits, live-restore). |
| `application` | Deploy directory (`/opt/assignment-portal`), `compose.prod.yaml`, `.env` (vault-sourced secrets), named volumes, image pull, systemd unit `assignment-portal.service`, health and version assertions. |

```bash
# Install collections (one-time on the controller)
ansible-galaxy collection install -r ansible/requirements.yml

# Dry-run preview
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml \
    --check --diff --ask-vault-pass

# Converge the host
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass

# Run again -- second run must report changed=0 (idempotency demonstration)
ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass

# Operate the service on the host
systemctl status  assignment-portal
systemctl restart assignment-portal
```

`ansible/group_vars/vault.yml` is referenced explicitly in `site.yml`; it currently contains
plaintext placeholders only. Replace them with real values and encrypt the file with Ansible Vault
before provisioning with secrets. Never commit real credentials in plaintext.

**Ansible detail:** [`docs/operations/ansible.md`](docs/operations/ansible.md)

> **Status:** The Ansible tree is written. No `ansible-playbook` has been run against any host.
> See `ansible.md` Section 9 for the full list of unexercised claims.

---

## 9. How rollback works

A rollback points the deployment back at the last version recorded as `success` in
`releases/ledger.tsv`. It does not undo database migrations.

```bash
# Manual rollback (operator command -- same script the pipeline calls automatically)
scripts\ops\rollback-release.cmd
```

The host-side script (`deploy/rollback-on-host.sh`):

1. Reads the ledger to find the last stable version.
2. Rewrites `.env` with that version's `APP_IMAGE_TAG`.
3. Runs `docker compose pull` and `docker compose up --no-build --wait`.
4. Waits for health.
5. Runs the smoke test.
6. Appends an outcome row to the ledger.

**Flyway/rollback compatibility rule:** Rollback is safe only when the schema change the failed
release carried is backward compatible (additive columns with defaults, new tables). A destructive
migration (`DROP COLUMN`, `RENAME`) forfeits the rollback path. See
[`docs/operations/rollback.md`](docs/operations/rollback.md) Section 2 for the full compatibility
table.

> **Status:** The rollback scripts are written. No rollback has fired. See `rollback.md` Section 9
> for the full list of unexercised claims.

---

## 10. Where configuration comes from

Configuration is externalised. The **same JAR is promoted between environments without being
rebuilt**; only the environment changes.

| Source | Committed? | Contains |
| --- | --- | --- |
| `src/main/resources/application.yml` | Yes | Safe defaults -- ports, Flyway, actuator exposure, session settings |
| `.env.example` | Yes | Variable **names** and documentation only. No values. |
| `.env` | No (git-ignored) | Real values for local development |
| Jenkins credentials store | No | `dockerhub-credentials`, `deploy-ssh-key` |
| `ansible/group_vars/vault.yml` | Yes (currently plaintext placeholders only) | Names/placeholders for DB and optional registry credentials; encrypt before adding real values |
| `deploy/env.production.example` | Yes | Template for the host `.env` |

Precedence (lowest to highest): `application.yml` then environment variables.

Key variables:

| Variable | Purpose |
| --- | --- |
| `DB_URL` | JDBC URL for MySQL |
| `DB_USERNAME` / `DB_PASSWORD` | Database credentials |
| `STORAGE_ROOT` | Absolute path to the PDF upload volume |
| `APP_SEED_ENABLED` | `true` to seed demo accounts (dev profile only) |
| `APP_SEED_STUDENT_PASSWORD` | Demo student password |
| `APP_SEED_REVIEWER_PASSWORD` | Demo reviewer password |
| `APP_VERSION` | Baked into the Docker image at build time |
| `SESSION_COOKIE_SECURE` | `false` without TLS; `true` in production |

Real secrets must never be written into a committed plaintext file. The currently tracked vault
file is not encrypted because it contains placeholders only; encrypt it before replacing those
placeholders with credentials. `CLAUDE.md` Section 7 rule 1 is the authoritative rule.

---

## 11. What is intentionally out of scope

These items are excluded deliberately, not by omission. The source of truth is
[`docs/requirements/mvp.md`](docs/requirements/mvp.md) Section 3.

| Item | Reason |
| --- | --- |
| Admin UI | `ADMIN` role and route guard exist; no management screens ship |
| Security-event audit trail (FR-24) | Outstanding; `audit_events` table and `AuditService` do not exist |
| Resubmission | A rejection is terminal in the MVP |
| TLS termination | Out of scope; `SESSION_COOKIE_SECURE=false` until a reverse proxy is added |
| Firewall rules | Host-level; out of scope for the application |
| Email notifications | No email provider |
| External identity provider | Local database accounts only |
| Backup retention / off-host copy | Backup scripts exist; retention policy and off-host transfer do not |
| Restore rehearsal | Backups are hypotheses until a rehearsed restore is recorded |
| Multi-role users | A user holds exactly one role in the MVP |
| Microservices | ADR-001 records the decision; superseding it requires a new ADR |

---

## 12. Repository map

```
.
+-- src/
|   +-- main/java/com/vit/assignmentportal/   <- Application source
|   +-- main/resources/
|   |   +-- application.yml                   <- Externalised configuration defaults
|   |   +-- db/migration/                     <- Flyway versioned migrations (V1-V3)
|   |   +-- static/                           <- CSS, JS assets
|   |   +-- templates/                        <- Thymeleaf templates
|   +-- test/java/com/vit/assignmentportal/   <- All test classes
+-- ansible/                                  <- Host provisioning playbook and roles
+-- deploy/                                   <- Host-side release and rollback scripts
+-- scripts/
|   +-- ci/                                   <- Scripts the Jenkinsfile calls
|   +-- ops/                                  <- Scripts for release, deploy, rollback
+-- docs/
|   +-- adr/                                  <- Architecture Decision Records (ADR-001-007)
|   +-- architecture/                         <- System overview, data model, security, etc.
|   +-- evidence/                             <- Task evidence (task-01.md to task-15.md)
|   +-- operations/                           <- Docker, Jenkins, Ansible, rollback, runbook
|   +-- requirements/                         <- mvp.md (FR-1 through FR-28)
|   +-- testing/                              <- Test strategy, CI quality gates
|   +-- viva/                                 <- Viva questions
+-- Dockerfile                                <- Multi-stage production image
+-- Jenkinsfile                               <- Declarative CI/CD pipeline
+-- compose.yaml                              <- Local development stack
+-- pom.xml                                   <- Maven build definition
+-- mvnw / mvnw.cmd                           <- Maven Wrapper (pins Maven 3.9.16)
+-- .env.example                              <- Environment variable template
+-- CLAUDE.md                                 <- Engineering handbook (rules of engagement)
+-- CONTRIBUTING.md                           <- Git workflow, commit conventions
+-- SECURITY.md                               <- Security policy and contact
```

---

## Key documents for a new reader

| Start here | Then read |
| --- | --- |
| This README | [`CLAUDE.md`](CLAUDE.md) -- the engineering handbook |
| [`docs/requirements/mvp.md`](docs/requirements/mvp.md) | [`docs/architecture/system-overview.md`](docs/architecture/system-overview.md) |
| [`docs/architecture/application-structure.md`](docs/architecture/application-structure.md) | [`docs/adr/`](docs/adr/) |
| [`docs/testing/strategy.md`](docs/testing/strategy.md) | [`docs/operations/jenkins-ci.md`](docs/operations/jenkins-ci.md) |
| [`docs/operations/release-runbook.md`](docs/operations/release-runbook.md) | [`docs/operations/rollback.md`](docs/operations/rollback.md) |
| [`docs/evidence/`](docs/evidence/) | [`docs/viva/questions.md`](docs/viva/questions.md) |
