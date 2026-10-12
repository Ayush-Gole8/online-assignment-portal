# Viva Questions and Technical Answers

This document covers the expected viva questions for the Online Assignment Portal project
and provides precise, honest technical answers grounded in the actual implementation.

---

## Architecture

### Why modular monolith and not microservices?

The deliverable is the pipeline, not the topology. A modular monolith minimises operational
complexity (one process, one database, one deployment unit) so the pipeline budget is spent on
CI, testing, Docker, Ansible, and rollback -- not on service discovery, distributed tracing, and
inter-service communication. The decision is recorded in ADR-001. Splitting the monolith was
explicitly not planned; if that ever changed it would require a new ADR superseding ADR-001.
The internal discipline -- package-by-feature, strict dependency direction -- means the seams
for a future split already exist without paying the operational cost today.

### Why Spring Boot?

Spring Boot provides the most complete ecosystem for the chosen constraints: embedded Tomcat,
Spring MVC, Spring Security, Spring Data JPA, Actuator, Flyway autoconfiguration, and Testcontainers
integration -- all with managed versions from a single BOM. The alternative (assembling these
libraries by hand) would require managing version compatibility across a large dependency set,
which is engineering work with no educational return for this project. Spring Boot's opinionated
defaults also match the target architecture: one executable JAR, one process, externalized
configuration.

### Why Maven Wrapper?

The Maven Wrapper pins the Maven version (3.9.16) in the repository itself. Every developer and
every CI agent builds with the same Maven, regardless of what is installed on the machine. This
is the same guarantee that a Docker image provides for the runtime: reproducibility by encoding
the tool version in a checked-in file rather than relying on the host environment. `./mvnw` needs
nothing on PATH; it bootstraps Maven itself from the configured URL.

### Why embedded Tomcat?

An embedded server makes the application a self-contained executable JAR -- one `java -jar`
command starts the process, and no Tomcat installation is needed on the deployment host. This
means the server is packaged with the application rather than installed separately on the
deployment host, and enables `./mvnw spring-boot:run` for local development. The alternative -- deploying
a WAR to an external Tomcat -- adds a deployment-specific configuration step between the build
and the run, which is the gap the embedded server eliminates.

---

## Persistence

### Why MySQL?

MySQL 8.4 is the same database family in development (via Testcontainers), in the Docker Compose
stack (the `db` service), and in the production deployment. This reduces the differences between
the engine tested and the engine deployed, although mutable image tags and patch versions still
need to be recorded for a particular run.
H2 (the common test database) has documented differences from MySQL in constraint enforcement,
type handling, and SQL dialect -- so a test suite that passes on H2 can fail on MySQL. Testcontainers
eliminates that gap by running real MySQL in tests. MySQL 8.4 is an LTS release with an
established Docker image, which is why `mysql:8.4` appears in both Testcontainers and Compose
configurations.

### Why Flyway?

Schema evolution must be explicit, versioned, and applied in a consistent order. Flyway provides
this: migration scripts (`V1__baseline.sql`, `V2__submissions.sql`, `V3__submission_status_history.sql`)
are checked into the repository alongside the code that requires them, so a checkout of a given
commit contains the schema that commit was tested against. `spring.jpa.hibernate.ddl-auto=validate`
means Hibernate validates the schema but never alters it -- Flyway is the single source of truth
for schema changes. A deployment that fails Flyway validation does not start, which is the safe
outcome. This is ADR-003.

### Why session auth rather than JWT?

The application is a server-rendered browser application, not an API-first SPA. Session cookies
work naturally with server-rendered HTML: the browser sends the cookie on every request, CSRF
tokens are injected into forms by Thymeleaf, and Spring Security handles all of this automatically.
JWT adds complexity (token refresh, client-side storage, expiry handling, revocation) that has
no benefit when the client is the browser and the server renders the pages. JWT is a stateless
mechanism for API consumers that cannot maintain server-side session state (e.g. mobile apps,
third-party API clients) -- none of which exist in this project. This is ADR-005.

---

## Storage and security

### Why PDF magic-byte validation?

A file with a `.pdf` extension and a `Content-Type: application/pdf` header is trivially faked
by renaming any file. The magic byte check reads the first four bytes of the upload and verifies
they are `%PDF` (0x25 0x50 0x44 0x46). That prefix can also be forged; it is a useful format-policy
check, not a PDF parser, malware scan, or proof that the document is safe. The check is server-side
because client-side validation is bypassed by any HTTP client. `FileValidationService` checks
configured size, extension, media-type policy, and signature before accepting a file.

### Why checksum (SHA-256)?

The SHA-256 digest of the uploaded file is computed from the bytes at upload time and stored with
the submission metadata. A later digest can be compared with the recorded value to detect
accidental corruption or an unexpected replacement. It could serve as a deduplication signal if a
future feature used it. A plain stored hash does not encrypt data, prove who uploaded it, or prevent
a privileged actor from changing both the file and its database digest.
`ChecksumService` computes the digest from the same in-memory buffer that `StorageService` writes
to disk, so the checksum and the stored bytes correspond to the same content.

### Why separate file storage?

File bytes and structured metadata have different concerns. Storing bytes in MySQL (as BLOBs)
couples the database size and backup strategy to the size and retention of file uploads. Storing
them on a separate persistent volume allows the database and file store to have distinct backup
procedures. They still need coordinated backup/restore because a metadata row without its file (or
vice versa) is inconsistent. `StorageService` keeps a clear boundary between the two: it stores bytes under a
server-generated storage identifier, and the database row stores that identifier (a key), not
the bytes. This is ADR-004.

---

## Application design

### Why optimistic locking?

Two reviewers could open the same submission and attempt to record different decisions
concurrently. Without any locking, the second write silently overwrites the first, and the
submission could end up in an inconsistent state (two history rows for one terminal decision,
or the wrong decision persisted). Optimistic locking via JPA `@Version` detects this race
without holding a database lock for the entire duration of the reviewer's reading and deciding:
the version column is incremented on each write, and the second writer's `UPDATE ... WHERE
version=0` fails because the version is now 1. Spring Data JPA maps this to an
`OptimisticLockingFailureException`, which the application maps to a user-visible message asking
the reviewer to try again. Pessimistic locking (SELECT FOR UPDATE) would hold a lock for the
duration of the HTTP request -- potentially seconds or minutes -- which is inappropriate for
a web application.

### Why audit history?

`SubmissionStatusHistory` records every status transition: who made it, from what status, to
what status, at what time, with what comment. This is essential for a review system because:
1. **Accountability:** who approved or rejected, and when, is recorded permanently.
2. **Dispute resolution:** a student who disagrees with a decision can point to the record.
3. **Debugging:** if a submission ends up in an unexpected state, the history shows how it got
   there.
The application writes history rows and exposes no update/delete workflow for them. This is not a
database-enforced immutability guarantee. Note: this is **workflow** history, not the security-event
audit trail (FR-24) which records sign-in attempts -- that trail is not yet implemented.

---

## Testing

### Why Testcontainers?

Testcontainers starts a real MySQL 8.4 container for the integration test suite. This means
Flyway migrations, `CHECK` constraints, foreign keys, and SQL dialect are exercised on the same
engine that runs in production -- not on H2, which differs from MySQL in constraint enforcement
and type handling. The tradeoff is that integration tests require a running Docker daemon (they
fail with `IllegalStateException: Could not find a valid Docker environment` without one -- the
exact error from the 2026-10-02 Run 1). The test reliability this buys -- catching real database
bugs -- is worth the infrastructure requirement.

### Why Selenium Page Objects?

Page Objects are a test design pattern that encapsulates the structure of a web page behind an
interface. Instead of each test containing raw `driver.findElement(By.id("..."))` calls, the
Page Object provides named methods (`loginPage.signIn(username, password)`,
`submissionPage.upload(file)`). This gives three properties:
1. **Readability:** tests read like a description of user behaviour, not a description of HTML.
2. **Maintainability:** when a page element changes (a form field ID, a button label), only the
   Page Object needs updating, not every test that uses the page.
3. **Separation of concerns:** the test asserts on behaviour; the Page Object knows the
   implementation.

### Why Selenium Manager?

Selenium Manager (built into Selenium 4) automatically downloads and caches the correct
ChromeDriver version matching the installed Chrome browser. Previously, maintaining a matching
ChromeDriver version was a manual task that frequently broke CI when Chrome auto-updated. Selenium
Manager eliminates this maintenance burden by resolving the driver version at test runtime.

---

## CI/CD pipeline

### Why Jenkinsfile?

A `Jenkinsfile` stored in the repository alongside the code means the pipeline definition is
versioned, code-reviewed, and subject to the same change process as the application. A pipeline
defined only in the Jenkins UI is invisible to code review, cannot be diffed, and diverges from
the code it tests. The Declarative syntax enforces structure (agent, environment, stages, post)
and makes the quality gates readable as code: `when { expression { currentBuild.currentResult
== 'SUCCESS' } }` is a gate that any reader can evaluate.

### Why artifact archiving?

The JAR is archived with `fingerprint: true` so that this exact binary can be traced to every
downstream job that consumes it -- a traceability chain from source commit to deployed artefact.
Test reports are archived so a failed build is diagnosable after the workspace is cleaned. Browser
failure screenshots are archived so a Selenium failure is diagnosable without re-running the suite.
The deployment report and smoke test output are archived so a failed deployment is diagnosable
without SSH access to the host. `cleanWs()` runs last, after everything has been archived, to
leave the agent clean for the next build.

---

## Docker

### Why Docker multi-stage build?

A single-stage Docker build that copies the whole JAR into the image would produce a ~80 MB
application layer that must be re-pushed on every commit, even when only one Java class changed.
The two-stage build extracts the JAR into four layers (dependencies / spring-boot-loader /
snapshot-dependencies / application) in stage 1 (the unpack stage), then copies those four
directories as four separate Docker layers in stage 2 (the runtime stage). The effect is
deterministic layer caching: a change to one Java class re-writes the `application/` layer
(a few hundred KB) and reuses the other three from the registry cache. The unpack stage is
otherwise disposable -- its metadata is not copied into the final image.

### Why non-root containers?

A container whose process runs as `root` (UID 0) grants anything that escapes the JVM full root
access on the host. A dedicated non-privileged user (`portal`, UID/GID 1001) limits the blast
radius of a container escape to what that user can do. The fixed UID/GID (rather than an
auto-assigned one) ensures that a volume written by one deployment is writable by the next --
two containers with different auto-assigned UIDs would fight over ownership of the volume files.
The Dockerfile creates the `portal` user, sets `USER portal`, and makes the uploads directory
writable by that user.

### Why versioned images (no `latest`)?

`latest` is a pointer that moves every time a new image is pushed. An image tagged `latest`
pulled today may be a different binary than the same tag pulled yesterday. A deployment that
pulls `latest` is not reproducible: the same Compose file started twice on two different days
may run different software. A version tag (`1.0.0`) makes the intended release clear and repeatable
in deployment configuration, but registries generally allow tags to be overwritten; the tag alone
is not cryptographic immutability. Record the registry digest to identify exact image bytes and
protect release tags through registry policy. The pipeline refuses to push `latest`. The version is also baked into the image as
`APP_VERSION`, so `/actuator/info` reports which version is actually running -- which is what
makes the smoke test's version assertion possible.

---

## Ansible and operations

### Why Ansible?

Ansible turns the question "what must be true about the host for the portal to run?" into
executable, versioned, declarative tasks. A supported Linux host can be provisioned by running
the playbook with the required inventory and credentials. That playbook can be run again after a configuration drift and will
converge the host back to the desired state. The alternative -- a shell script -- is typically
not idempotent (running it twice breaks things it already set up) and does not declare what
state it wants, only what commands to run. Ansible roles also separate concerns: the `docker`
role knows nothing about the portal, and could be reused for a second application on the same
host.

### Why idempotency?

Idempotency means repeated runs against unchanged inputs converge to the same desired state
without unintended side effects. This is the property that makes "run
the playbook to fix a problem" a safe operation: you cannot make the situation worse by running
it again. It is also the property that makes the `changed=0` second run the test for
idempotency -- if the second run shows changes, a task is not idempotent and needs to be fixed.
Ansible modules (apt, file, copy, systemd, etc.) are idempotent by design; custom shell tasks
require explicit guards (`creates:`, `when:`) to achieve the same property.

### Why Docker Compose for deployment?

Docker Compose expresses the multi-container application (the `app` and `db` services, the
`portal` network, the `app-uploads` and `mysql-data` volumes) in one declarative file that is
both the local development environment and the production deployment. The `compose.prod.yaml`
variant references a published image by tag rather than `build:`, so a deployment host runs
the exact tested artefact. `docker compose up --no-build --wait` is a single command that
pulls the image, starts both services in dependency order, and waits until both health checks
pass. The alternative -- raw Docker commands -- requires manually managing network names, volume
names, start order, and dependency waiting.

---

## Rollback

### Why rollback?

Deployments fail. The health check and smoke test exist to detect a bad deployment before traffic
reaches it, but detection is only useful if recovery is automatic and fast. The rollback mechanism
restores the last version recorded as `success` in the release ledger, verifies health again, and
records the outcome -- without human intervention. The key design property is that the automatic
failure path and the manual operator path are the same command (`rollback-release.cmd` calls
`rollback-on-host.sh`), so the path an operator takes at three in the morning is the same one a
failing pipeline takes.

### Flyway/rollback compatibility

Rollback restores the previous **application** against the **current** database schema. Flyway
migrations are forward-only and are not reversed. The previous application therefore runs only if
the schema the failed release applied is backward compatible with it. Additive changes (new table,
nullable column, column with a default) are rollback-safe; destructive changes (`DROP COLUMN`,
`RENAME`, `NOT NULL` without a default) are not. A release that needs to be rollback-able must be
designed with backward-compatible schema changes. The rule is documented in `rollback.md` Section 2
with a full compatibility table. The three migrations shipped so far (V1: `users`, V2: `submissions`,
V3: `submission_status_history`) are all additive and rollback-safe.

---

## Secret management

### How are secrets managed?

Secrets are never committed to the repository in any file. The mechanisms by context:

| Context | Mechanism |
| --- | --- |
| Local development | `.env` file (git-ignored), values provided by the developer |
| Integration tests | Testcontainers generates a random MySQL password for each run |
| CI pipeline | Jenkins credentials store (`dockerhub-credentials`, `deploy-ssh-key`); bound into stage environment for the duration of one stage |
| Ansible provisioning | `ansible/group_vars/vault.yml`, encrypted with `ansible-vault`; decrypted at playbook run time |
| Deployment host | `/opt/assignment-portal/.env`, written by the `application` Ansible role from vault variables; never logged or printed |

The committed `ansible/group_vars/vault.yml` currently contains plaintext placeholders, not real
credentials and not encrypted content. Before using real secrets, replace the placeholders and
encrypt the file with Ansible Vault; provide the vault password outside the repository. The rule
in `CLAUDE.md` Section 7 is never to commit real credentials. A leaked credential requires
rotation, and its old value remains in Git history.
