# Toolchain

**Status:** Partially recorded — versions used by the last recorded build are captured; the
tool-version rows marked *unverified* have still not been checked on this machine
**Related:** [CLAUDE.md §3](../../CLAUDE.md) · [Contribution workflow](../../CONTRIBUTING.md)

---

## 1. How to use this document

The tables below record **what this machine actually has**, not what the project hopes it has.
A pipeline that is verified against an assumed version is verified against nothing.

**Procedure — run once per machine, and again whenever a tool is upgraded:**

1. Run the verification command for each row.
2. Paste the **actual** output into the *Actual version* column.
3. Set *Verified* to the date you ran it.
4. If the actual version does not satisfy *Required*, either upgrade the tool or record a
   deliberate, justified deviation in the Notes column — do not silently proceed.

Until a row is filled in, treat it as **unverified** and do not claim the prerequisite is met.

> **Reading the current state.** Two different kinds of value appear below and they are not
> equivalent:
>
> - **Declared** — read out of `pom.xml`, `compose.yaml` or `Dockerfile`. This is a fact about
>   the repository, verified by inspection.
> - **Unverified** — the version a resolving build or a local machine would report. **Not yet
>   established**, because the tool-version commands have not been run on this machine. A build
>   *has* run — see §9 — but it recorded the versions of the tools it loaded, not the version of
>   every tool listed below.
>
> A declared version is not proof the build resolves it. Fill in the unverified rows before
> claiming any prerequisite is met.

## 2. Application runtime and build

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| Java (JDK) | 21 (LTS) | `mvn -version` | **21.0.12.1** (Eclipse Adoptium) — **in use** | 2026-10-01 | The build log shows `Starting ApplicationContextIT using Java 21.0.12.1`, and `javac [debug parameters release 21]` confirms the bytecode target. Selected by `JAVA_HOME`; the JDK 26 on `PATH` is no longer picked up |
| Maven | Provided by the Maven Wrapper, pinned to 3.9.16 | `./mvnw -version` | **3.9.16** | 2026-10-01 | The wrapper is committed — `.mvn/wrapper/maven-wrapper.properties`, `wrapperVersion=3.3.4`, `distributionType=only-script`, distribution URL `apache-maven-3.9.16-bin.zip` — so the build no longer depends on whichever Maven happens to be on `PATH`. Before it existed, `mvn -version` resolved to a VS Code extension's bundled Maven rather than `C:\DevTools\apache-maven-3.9.16`. `requireMavenVersion [3.9,)` passes either way |
| Spring Boot | 4.1.1 | `pom.xml` → `<parent>` | **4.1.1** — resolves, builds, and boots | 2026-10-01 | The parent resolves, the JAR is built, `spring-boot:4.1.1:repackage` runs, and the context starts (`:: Spring Boot :: (v4.1.1)`). Hibernate ORM **7.4.5.Final**. The milestone's largest risk is retired |

## 3. Data stores

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| MySQL Server | 8.4 (LTS) | Testcontainers build log | **8.4.11** | 2026-10-01 | Started by Testcontainers during `ApplicationContextIT`; Hibernate reports `Database version: 8.4.11`. The Compose service in `compose.yaml` has never been started |
| MySQL Connector/J | Managed by the Spring Boot BOM | build log | Loaded and connected — **exact version unverified** | 2026-10-01 | Hibernate reports `Database driver: MySQL Connector/J` and HikariCP opened a connection |
| Flyway | Managed by the Spring Boot BOM | BOM inspection | **12.4.0** — but its **autoconfiguration was missing** | 2026-10-01 | `<flyway.version>12.4.0</flyway.version>` in `spring-boot-dependencies-4.1.1.pom`. Boot 4 moved `FlywayAutoConfiguration` into the **`spring-boot-flyway`** module, so `flyway-core` alone is inert — the context starts and no migration runs. Fixed by adding `spring-boot-starter-flyway`; `flyway-mysql` remains required |

## 4. Containerisation

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| Docker Engine | 24+ | Testcontainers handshake | **29.6.1** (API 1.55) | 2026-10-01 | Reported by Testcontainers: `Server Version: 29.6.1`, `Operating System: Docker Desktop (containerized)`, 7794 MB total memory |
| Docker Compose | v2 plugin (`docker compose`), recent enough to carry `up --wait` | `docker compose version` | `TBD` — **unverified** | — | The v2 plugin form, not the legacy `docker-compose` binary. The release process depends on `docker compose up -d --wait` ([`docker-cd.md` §3](docker-cd.md) step 8), which the legacy binary does not have. The Validate stage prints this version on every build, so the first Jenkins run records it |
| Docker registry | Docker Hub (`docker.io`) | `docker login docker.io` | **Declared** — Docker Hub | — | Where releases are published ([`docker-cd.md`](docker-cd.md) §2). The *host* pulls from it, the *agent* pushes to it. `IMAGE_REPOSITORY` is host-owned; the pipeline's account is supplied per release, never committed |
| Docker Buildx | Current | `docker buildx version` | `TBD` — **unverified** | — | Needed for multi-stage, layer-cached builds |
| Runtime base image | Java 21 (LTS), maintained, not `latest` | `docker buildx imagetools inspect eclipse-temurin:21-jre` | **Declared** — `eclipse-temurin:21-jre` | — | Eclipse Temurin. **Pinned by tag, not by digest**, so a patch release can still change under a rebuild — the residual gap [`docker.md` §2.1](docker.md) records, with the command that closes it |
| MySQL image | 8.4 (LTS) | `docker image inspect mysql:8.4` | **Declared** — `mysql:8.4` | — | Declared in `compose.yaml`. The Compose service itself has not been started — the integration tests run MySQL through Testcontainers instead |

> Testcontainers requires a working Docker daemon. Confirm with `docker info` before running
> integration tests; a stopped daemon produces confusing failures. **`./mvnw clean verify`
> cannot pass without it.**

## 5. CI and provisioning

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| Jenkins | 2.4xx LTS or later | Jenkins UI → *About Jenkins* | `TBD` — **unverified** | — | Recorded from the controller, not a local install. Plugins required by the release stages: **Credentials Binding** and **SSH Agent**, in addition to those listed above |
| OpenSSH client (`ssh`, `scp`) | Any version with `BatchMode` | `ssh -V` | `TBD` — **unverified** | — | Used by the deploy script to reach the host. The **host key must be in the agent's `known_hosts`**: verification is not disabled and must not be ([`docker-cd.md` §4](docker-cd.md)) |
| Ansible | 2.16+ | `ansible --version` | `TBD` — **unverified** | — | Control node. The managed host needs only SSH, Python 3, and sudo. **Now used:** [`ansible/`](../../ansible) provisions the deployment host ([`ansible.md`](ansible.md)). It has not been run — neither a first provisioning nor the second converge run — so this row stays *unverified*. The `community.docker` collection is required (`ansible/requirements.yml`) |
| Ansible Vault | Ships with `ansible-core` | `ansible-vault --version` | `TBD` — **unverified** | — | Encrypts [`ansible/group_vars/vault.yml`](../../ansible/group_vars/vault.yml). The file is currently plaintext placeholders and **must be encrypted before it holds a real value** |
| Git | 2.40+ | `git --version` | `TBD` — **unverified** | — | Must be able to read tags: the **Release Version** stage runs `git describe --tags --exact-match HEAD` |

## 6. Testing and quality

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| JUnit 5 | Managed by the Spring Boot BOM | `./mvnw dependency:tree` | Managed — **unverified** | — | Arrives via `spring-boot-starter-test` |
| Testcontainers | Managed by the Spring Boot BOM | build log | **2.0.5** | 2026-10-01 | `Testcontainers version: 2.0.5`. Testcontainers **2.x renamed its modules**: `testcontainers-mysql` and `testcontainers-junit-jupiter`, not the 1.x `mysql` / `junit-jupiter` |
| Selenium | 4.x | `./mvnw dependency:tree` | **Declared** — `selenium-java`, test scope, version managed by the Spring Boot BOM (4.x) — **exact version unverified** | — | Drives the two `*E2E` classes under `src/test/java/.../e2e/`, which run only under the `e2e` profile. Declared without a `<version>`, so the BOM resolves it; `./mvnw dependency:tree` records which release that is |
| Chrome / Chromium | Current stable | `chrome --version` | `TBD` — **unverified** | — | Selenium end-to-end driver in CI. `BaseE2ETest` launches `--headless=new`; nothing in the repository pins a browser build |
| Browser driver | Managed by Selenium Manager | — | `TBD` — **unverified** | — | Selenium Manager resolves the driver for the installed Chrome at run time, so no driver is committed and none is on `PATH` by design |

## 7. Browser support matrix

The application is server-rendered and must work without JavaScript (NFR-2).

| Browser | Supported | Notes |
| --- | --- | --- |
| Chrome / Edge (current stable) | Yes | Primary target; the Selenium suite runs on Chrome |
| Firefox (current stable + ESR) | Yes | Second target |
| Safari | Best effort | Not covered by automated tests |
| Internet Explorer | No | Not supported |

### Vendored front-end assets

The UI links no CDN — a build agent with no route to the public internet must render the same pages
([CLAUDE.md §1](../../CLAUDE.md), enforced by `UiRenderingIT.linksTheVendoredStylesheets`). The
stylesheets are therefore committed under `src/main/resources/static/css/` and served from `/css/**`.

| Asset | Version | Source | Licence | Notes |
| --- | --- | --- | --- | --- |
| Bootstrap CSS | **5.3.3** | `cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css`, vendored on 2026-10-02 | MIT (header retained in the file) | CSS only — no bundle, no Popper, no JavaScript (NFR-2). The templates use 5.3 utilities (`text-body-secondary`), so the major.minor is load-bearing. `UiRenderingIT.servesTheLinkedStylesheetsAsCss` asserts it is served as `text/css`, which is what was missing while the file was absent |
| `portal.css` | — | this repository | — | This application's own overrides, loaded after Bootstrap |

## 8. Recording results

The build now exists, so dependency versions can be captured mechanically rather than by
inspection. Run this on the first machine that has Java 21 and a working Docker daemon, and
paste the output into the tables above:

```bash
# Produce the resolved dependency list for the record
./mvnw dependency:tree -DoutputFile=docs/evidence/dependency-tree.txt

# Record the toolchain actually used for the build
./mvnw -version
java -version
docker --version
docker compose version
```

## 9. Outstanding gaps on this machine

Recorded 2026-10-01 from the output of an actual `mvn clean verify` run, revised after the
Milestone 2 run of the same day, and revised again after the three **2026-10-02** runs. This section
describes **observed** state, not expected state.

The Milestone 2 run **compiled both source sets, packaged the Spring Boot JAR, started the Spring
context, and ran 30 integration tests and 5 unit tests against a real MySQL 8.4.11 container — all
passing.**

**Three runs on 2026-10-02, and between them they moved this section on twice over.**

**Run 1 — no Docker daemon.** Both source sets compiled and the **unit** suite passed — **60
Surefire tests, 0 failures, 0 errors** — then the full **integration** suite was discovered, **112
tests across 10 classes**, and **every one of the 112 errored at class initialisation**, before any
test body ran:

```
Caused by: java.lang.IllegalStateException: Could not find a valid Docker environment.
    at org.testcontainers.containers.GenericContainer.start(GenericContainer.java:316)
    at com.vit.assignmentportal.MySqlIntegrationTest.<clinit>(MySqlIntegrationTest.java:59)
```

**Run 2 — Docker up, and the suite executed: 112 integration tests, 2 failures, 0 errors.** Both
failures were real defects. Neither was environmental, and both had been invisible while run 1 was
the most recent thing anyone had seen:

- `SubmissionUploadIT` — a refused upload came back with the page's own `<title>` text in the Title
  field. A Thymeleaf fragment parameter is a **local variable over everything the fragment splices
  in**, so `layout.html`'s `page(title, body)` shadowed the flashed `title` model attribute that
  `upload.html` renders through `th:value="${title}"`. Fixed by renaming the parameters
  `pageTitle`/`pageContent`, which cannot collide with an ordinary domain noun.
- `UiRenderingIT` — `GET /error` with no `Accept` header reaches `BasicErrorController`'s JSON
  handler, so no view is resolved and the test failed on "No ModelAndView found" rather than on
  anything about the page. Fixed by driving the request the way a container drives an error
  dispatch: an `Accept: text/html` header and an `ERROR_STATUS_CODE` request attribute.

**Run 3 — both fixes landed; 113 integration tests, 1 failure, 0 errors.** The two defects above are
gone, and the count rose by one because the error-page test was split into a 500 case and a 404
case. The remaining failure was a third real defect, found by an assertion written to look for
something else — which is the most useful thing about it.

`rendersTheErrorPageWithoutExposingInternals` asserts the rendered error page does not contain the
word "exception". The page did:

```text
  it directly, and nothing has to route to it: a 404, a 500, or an exception that reaches
  the container all end up here.
```

That is not page copy. It is `error.html`'s own explanatory comment. Thymeleaf emits an ordinary
HTML comment verbatim into the response — `<!-- … -->` is **content**, not whitespace — so every
page was shipping its engineering notes to every browser that opened it. On the error page those
notes name `BasicErrorController`, `SecurityConfiguration`, and the `server.error.include-message:
never` setting that keeps the failure message blank. Publishing a map of that machinery to an
anonymous visitor is the disclosure `CLAUDE.md` §7 rule 11 forbids, on the one page an anonymous
visitor is most likely to reach.

Fixed by writing every comment that can reach a browser as a Thymeleaf **parser-level** comment,
which the parser discards before rendering: 51 comments across 18 of the 19 template files.
(`fragments/portal-header.html`, which nothing references, was the nineteenth; it has since been
deleted.) The assertion itself
was left exactly as it was. It now passes because nothing reaches the browser, which is the right
reason and the opposite of weakening the test to fit the output.

Two gaps remain.

| Gap | Why it matters | How to close it |
| --- | --- | --- |
| **The run-3 fix is written but not re-run.** | Comments are now parser-level, so nothing should reach the browser — but that is an argument, not a result, and the change touched 18 template files, which is a wide blast radius for an unexecuted edit. The new `shipsNoTemplateCommentaryToTheBrowser` assertion has never run. The two run-2 defects are in the same position: fixed, and unproven. | Re-run `./mvnw clean verify` with the Docker daemon running, then record the outcome here and under `docs/evidence/`. |
| **One test emits a deprecation warning under Testcontainers 2.0.5.** | `ApplicationContextIT` "uses or overrides a deprecated API". Not fatal, but a clean build is this repository's standard. The warning was observed against the 5-test version of that class, so it needs re-checking at the current revision. | Re-run with `-Xlint:deprecation` to name the API, then replace it. |

**Closed on 2026-10-02 — the Docker and compilation gaps, by real output:**

- ~~Nothing has run since Milestone 3 was written; the review suite has never been compiled.~~
  **Closed.** Both source sets compile at this revision, and `ReviewWorkflowIT` (17),
  `SubmissionConcurrencyIT`, `StudentDashboardIT` (6) and `UiRenderingIT` (8) all executed in run 2.
- ~~No Docker engine was reachable, so no integration test could run.~~ **Closed in run 2**, which
  ran the whole integration suite against a containerised MySQL. Note what this cost: while the
  daemon was down, run 1 reported 112 errors that said nothing about the code, and the two defects
  that *did* exist stayed hidden behind them until run 2.
- ~~The two defects run 2 found.~~ **Closed in run 3**, which reported neither: the upload test saw
  the student's own title in the Title field, and the error-page test resolved a view instead of
  failing on "No ModelAndView found". Both were closed by a run that reported them absent, not by
  the argument that the fixes were right.

**Closed on 2026-10-01 — resolved by real output, not by assumption:**

- ~~`JAVA_HOME` does not point at a JDK 21.~~ **Closed.** With `JAVA_HOME` set to the JDK 21
  root, `requireJavaVersion` passed and the build ran on Java 21.0.12.1. The earlier failure
  came from using `Test-Path` to *check* a path rather than to *assign* one.
- ~~`spring-boot-starter-parent:4.1.1` may not resolve.~~ **Closed.** It resolves, the JAR
  builds, and the context boots.
- ~~Maven is not installed.~~ **Closed.** Maven 3.9.16.
- ~~The Testcontainers artifact ids use the 1.x names.~~ **Closed.** Testcontainers 2.x renamed
  its modules; the `testcontainers-` prefix is correct.
- ~~Nothing has compiled.~~ **Closed.** `javac ... release 21` compiled both source sets, the
  Boot JAR was repackaged, and the integration test ran against a real container.
- ~~Flyway's autoconfiguration is missing, so no migration runs.~~ **Closed.** Adding
  `spring-boot-starter-flyway` to the POM was verified by a re-run rather than assumed:
  `appliesBaselineMigration_andCreatesUsersTable` passes against a containerised MySQL 8.4.11,
  which it cannot do unless `V1__baseline.sql` reached the database.
- ~~The Maven Wrapper is not generated.~~ **Closed.** `mvnw`, `mvnw.cmd` and
  `.mvn/wrapper/maven-wrapper.properties` are committed and pin Maven 3.9.16 with
  `distributionType=only-script`, so `./mvnw` needs no local Maven install. See §2.

Everything still open is either an unverified fix or a compiler warning. Nothing here is waiting on
code that has not been written.

> **On the entries closed above by a re-run rather than by reasoning.** The Flyway defect was
> a *fix* until a run proved it, and this document said so while it was one. Closing a gap here
> requires output, not a plausible explanation — the standard the evidence rules are written to
> (`docs/evidence/README.md` §3). Run 3 is where that paid off: the two run-2 defects are closed
> because a run reported them gone, not because their fixes looked right. The run-3 fix is in
> exactly the position they were in before it ran, and stays listed as an open gap for the same
> reason. Note also what this section still cannot claim: the submission and review slices **have**
> now been executed — that is what runs 2 and 3 did — but they have not been executed *to green*.
> The last green build it cites remains Milestone 2's, which is why the tool-version rows in §2–§6
> are still marked *unverified* at this revision.

**Added 2026-10-07, and not run by any build:** the release and deployment tooling —
`scripts/ops/`, `deploy/compose.prod.yaml`, `deploy/deploy-on-host.sh`. No image has been pushed,
no host contacted, no rollback fired, and **no command in this section has been executed for any of
them**. The Docker Compose, OpenSSH, Docker Hub and registry rows in §4–§5 were added to name a
requirement that the release process depends on, not to record a version anyone observed; they
remain *unverified* for the same reason as every other row here. Nothing in this document moves
from *unverified* to a real version until a `vX.Y.Z` tag is built end to end and the run recorded
under [`docs/evidence/`](../evidence/README.md) — which still cannot happen before a green
`./mvnw clean verify`.

## 10. Engineering review notes (2026-10-12)

- **Java enforcement is in the build.** `maven-enforcer-plugin` requires Java `[21,22)` and
  Maven 3.9 or newer; the checked-in Maven Wrapper selects 3.9.16. The build also rejects
  duplicate dependency declarations with conflicting versions. Use `./mvnw` (or `mvnw.cmd` on
  Windows) so the wrapper, rather than a machine-installed Maven, selects the build tool.
- **Style policy:** `.editorconfig` is the repository-wide source for whitespace, line endings,
  and indentation. No formatter or static-analysis suite is added; the project has no demonstrated
  need for a larger build toolchain. Keep code review focused on the feature packages and the
  constraints already enforced by compilation, Enforcer, migrations, and tests.
- **Source review:** no generic utility bucket or clearly orphaned production package was found;
  controllers delegate, repositories hold persistence queries, and read models centralize entity
  mapping. `SubmissionService` is the largest class (548 lines) because it owns upload/file and row
  transaction coordination plus submission reads and review transitions. It is a maintenance
  hotspot, but splitting it now would add a boundary across the security/transaction ownership
  model without test evidence. Keep future changes behavior-focused and revisit extraction when
  tests can lock down that boundary.
- **Clean build command:** `./mvnw clean verify` is the deterministic full command and requires
  Docker for Testcontainers. On 2026-10-12, `mvnw.cmd -B clean verify -DskipTests` completed
  successfully on this Windows workspace: Enforcer passed Java and Maven version and duplicate
  dependency checks; Maven compiled 49 production and 42 test source files for release 21 and
  packaged the executable JAR. Surefire and Failsafe explicitly skipped execution. This is a
  compilation/package check, not test evidence; retain the last-run evidence in §9 and
  `docs/testing/test-strategy.md` rather than treating it as a green test suite.
- **Infrastructure checks:** both Compose files passed `docker compose config --quiet` using their
  example environment files. The Docker daemon was unavailable, so the image was not built. Ansible
  syntax validation was also unavailable because `ansible-playbook` is not installed; provisioning
  and idempotency remain source-reviewed, not executed.
- **Container reproducibility remains incomplete:** the application image is tagged with a
  version/commit by CI, but its Java and MySQL base references are mutable tags, not digests.
  Docker Compose and Ansible/collection versions also need recording before an operator can
  reproduce a deployment byte-for-byte. See the open items in
  [`security-review.md`](../security/security-review.md).
