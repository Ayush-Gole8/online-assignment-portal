# Toolchain

**Status:** Partially recorded — repository-declared versions captured; local machine versions still unverified
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
> - **Declared** — read out of `pom.xml` or `deploy/docker-compose.yml`. This is a fact about
>   the repository, verified by inspection.
> - **Unverified** — the version a resolving build or a local machine would report. **Not yet
>   established**, because `./mvnw clean verify` and the tool-version commands have not been
>   run on this machine.
>
> A declared version is not proof the build resolves it. Fill in the unverified rows before
> claiming any prerequisite is met.

## 2. Application runtime and build

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| Java (JDK) | 21 (LTS) | `mvn -version` | **21.0.12.1** (Eclipse Adoptium) — **in use** | 2026-10-01 | The build log shows `Starting ApplicationContextIT using Java 21.0.12.1`, and `javac [debug parameters release 21]` confirms the bytecode target. Selected by `JAVA_HOME`; the JDK 26 on `PATH` is no longer picked up |
| Maven | Provided by the Maven Wrapper | `mvn -version` | **3.9.16** — resolved from a **different** install than expected | 2026-10-01 | `mvn -version` reports `Maven home: C:\Users\Ayush\AppData\Roaming\Code\User\globalStorage\pleiades.java-extension-pack-jdk\maven\latest`, not `C:\DevTools\apache-maven-3.9.16`. `requireMavenVersion [3.9,)` **passed**. This ambiguity is precisely what the wrapper removes — still not generated, see §9 |
| Spring Boot | 4.1.1 | `pom.xml` → `<parent>` | **4.1.1** — resolves, builds, and boots | 2026-10-01 | The parent resolves, the JAR is built, `spring-boot:4.1.1:repackage` runs, and the context starts (`:: Spring Boot :: (v4.1.1)`). Hibernate ORM **7.4.5.Final**. The milestone's largest risk is retired |

## 3. Data stores

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| MySQL Server | 8.4 (LTS) | Testcontainers build log | **8.4.11** | 2026-10-01 | Started by Testcontainers during `ApplicationContextIT`; Hibernate reports `Database version: 8.4.11`. The Compose service in `deploy/` is still unverified |
| MySQL Connector/J | Managed by the Spring Boot BOM | build log | Loaded and connected — **exact version unverified** | 2026-10-01 | Hibernate reports `Database driver: MySQL Connector/J` and HikariCP opened a connection |
| Flyway | Managed by the Spring Boot BOM | BOM inspection | **12.4.0** — but its **autoconfiguration was missing** | 2026-10-01 | `<flyway.version>12.4.0</flyway.version>` in `spring-boot-dependencies-4.1.1.pom`. Boot 4 moved `FlywayAutoConfiguration` into the **`spring-boot-flyway`** module, so `flyway-core` alone is inert — the context starts and no migration runs. Fixed by adding `spring-boot-starter-flyway`; `flyway-mysql` remains required |

## 4. Containerisation

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| Docker Engine | 24+ | Testcontainers handshake | **29.6.1** (API 1.55) | 2026-10-01 | Reported by Testcontainers: `Server Version: 29.6.1`, `Operating System: Docker Desktop (containerized)`, 7794 MB total memory |
| Docker Compose | v2 (`docker compose`) | `docker compose version` | `TBD` — **unverified** | — | The v2 plugin form, not the legacy `docker-compose` binary |
| Docker Buildx | Current | `docker buildx version` | `TBD` — **unverified** | — | Needed for multi-stage, layer-cached builds |

> Testcontainers requires a working Docker daemon. Confirm with `docker info` before running
> integration tests; a stopped daemon produces confusing failures. **`./mvnw clean verify`
> cannot pass without it.**

## 5. CI and provisioning

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| Jenkins | 2.4xx LTS or later | Jenkins UI → *About Jenkins* | `TBD` — **unverified** | — | Recorded from the controller, not a local install |
| Ansible | 2.16+ | `ansible --version` | `TBD` — **unverified** | — | Control node; the managed host needs only SSH and Python |
| Git | 2.40+ | `git --version` | `TBD` — **unverified** | — | |

## 6. Testing and quality

| Tool | Required | Verification command | Actual version | Verified | Notes |
| --- | --- | --- | --- | --- | --- |
| JUnit 5 | Managed by the Spring Boot BOM | `./mvnw dependency:tree` | Managed — **unverified** | — | Arrives via `spring-boot-starter-test` |
| Testcontainers | Managed by the Spring Boot BOM | build log | **2.0.5** | 2026-10-01 | `Testcontainers version: 2.0.5`. Testcontainers **2.x renamed its modules**: `testcontainers-mysql` and `testcontainers-junit-jupiter`, not the 1.x `mysql` / `junit-jupiter` |
| Selenium | 4.x | — | **Not yet a dependency** | — | Arrives with the end-to-end milestone |
| Chrome / Chromium | Current stable | `chrome --version` | `TBD` — **unverified** | — | Selenium end-to-end driver in CI |
| Browser driver | Managed by Selenium Manager | — | `TBD` — **unverified** | — | |

## 7. Browser support matrix

The application is server-rendered and must work without JavaScript (NFR-2).

| Browser | Supported | Notes |
| --- | --- | --- |
| Chrome / Edge (current stable) | Yes | Primary target; the Selenium suite runs on Chrome |
| Firefox (current stable + ESR) | Yes | Second target |
| Safari | Best effort | Not covered by automated tests |
| Internet Explorer | No | Not supported |

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

Recorded 2026-10-01 from the output of an actual `mvn clean verify` run. This section describes
**observed** state, not expected state.

The build now **compiles, packages, starts the Spring context, and boots MySQL 8.4.11 in a
container.** One defect remains open.

| Gap | Why it matters | How to close it |
| --- | --- | --- |
| **Flyway's autoconfiguration was missing** — the remaining defect. | Declaring `org.flywaydb:flyway-core` alone is inert on Spring Boot 4: the autoconfiguration moved into the `spring-boot-flyway` module. The context started green and **no migration ran**, so `V1__baseline.sql` never reached the database — a failure a health check cannot see. Two tests caught it. | **Fixed in the POM** by adding `spring-boot-starter-flyway`. Awaiting a re-run — until `mvn clean verify` is green this is a *fix*, not a resolution. |
| **The Maven Wrapper is not generated.** | `./mvnw clean verify` cannot run. Builds depend on whichever Maven is on `PATH` — and `mvn -version` shows it resolving to a VS Code extension's bundled Maven, not the intended `C:\DevTools` install. That ambiguity is exactly what the wrapper removes. | `mvn -N wrapper:wrapper -Dmaven=3.9.16 -Dtype=only-script`, then commit the three generated files. |
| **One test emits a deprecation warning under Testcontainers 2.0.5.** | `ApplicationContextIT` "uses or overrides a deprecated API". Not fatal, but a clean build is this repository's standard. | Re-run with `-Xlint:deprecation` to name the API, then replace it. |

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

Everything still open is either the wrapper or confirmation of the Flyway fix.

> **Why this section records gaps rather than a generated wrapper.** The wrapper scripts are
> non-trivial bootstrap code that downloads and verifies a Maven distribution. Reproducing
> them by hand, unrun and unverified, would put a build tool into the repository that looks
> authoritative and may be subtly broken — the exact failure mode this project's evidence
> rules are written against (`docs/evidence/README.md` §3). A recorded gap is honest; a
> fabricated build script is a trap.
