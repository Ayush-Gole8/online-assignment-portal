# CI Quality Gates

**Status:** The pipeline definition is committed and **has not been executed on a Jenkins agent**.
Every statement below describes the `Jenkinsfile`, not an observed run. The one exception is called
out where it occurs — that a script *would* behave as described is a reading of the script, not a
result of running it.
**Related:** [Jenkinsfile](../../Jenkinsfile) · [ci operations](../operations/jenkins-ci.md) ·
[test strategy](strategy.md) · [E2E testing](e2e-testing.md) · [CLAUDE.md §9](../../CLAUDE.md) ·
[defect demonstration](../evidence/defect-demonstration.md)

---

## 1. The rule

> **Any failed automated quality gate fails the pipeline, and a failed pipeline builds no Docker
> image and deploys nothing.**

The word doing the work in that sentence is *structurally*. The gate is not a convention that a
future edit could forget to honour; it is expressed twice, in two mechanisms that fail
independently:

| Mechanism | Where | What it does |
| --- | --- | --- |
| `catchError(buildResult: 'FAILURE', …)` | On every test stage | Sets the build result to `FAILURE` at the moment a stage fails, and lets the pipeline continue so the reports still publish |
| `when { expression { currentBuild.currentResult == 'SUCCESS' } }` | On **Docker Build** and **Deployment** | Refuses to evaluate the stage's steps at all unless nothing upstream has failed |

A stage that ran `catchError` has already made `currentResult` `FAILURE` before the pipeline
reaches Docker Build. Docker Build's `when` therefore never opens, the stage shows as *skipped*, and
Deployment — which carries the same condition — never opens either. The image is not built, so there
is nothing to release; nothing is deployed, because nothing was released.

Adding a new test stage means adding it *with* its `catchError`. Adding a new downstream consumer
means giving it the same `when`. Neither is optional, and both are visible in a diff.

## 2. The pipeline, in order

| # | Stage | Command | Proves | On failure |
| --- | --- | --- | --- | --- |
| 1 | **Checkout** | `checkout scm` | The exact revision under test is the one pushed | Aborts |
| 2 | **Validate** | `java -version`, `git --version`, `docker version`, `docker compose version` | The agent has the toolchain, and which versions | Aborts |
| 3 | **Build** | `.\mvnw.cmd -B clean test-compile` | Both source sets compile | `FAILURE`, continues |
| 4 | **Unit Tests** | `.\mvnw.cmd -B test` | The business rules, no container, no context | `FAILURE`, continues |
| 5 | **Integration Tests** | `.\mvnw.cmd -B failsafe:integration-test failsafe:verify` | Persistence, migrations, security wiring, constraints — against a real MySQL 8.4 | `FAILURE`, continues |
| 6 | **Package** | `.\mvnw.cmd -B package -DskipTests` | The executable Spring Boot JAR — the artefact the next two stages use | `FAILURE`, continues |
| 7 | **Start Test Environment** | `scripts\ci\start-database.cmd` → `start-application.cmd` → `wait-for-health.cmd` | MySQL 8.4 is healthy, the packaged JAR is running, and it answers `/actuator/health` | `FAILURE`, continues |
| 8 | **Selenium E2E** | `scripts\ci\run-e2e.cmd` | A real browser completes the student and reviewer journeys against that deployment | `FAILURE`, continues |
| 9 | **Publish Reports and Screenshots** | `junit`, `archiveArtifacts` | Surefire, Failsafe and E2E results, and the failure screenshots, attached to the build | Reports |
| 10 | **Docker Build** | `docker build -t online-assignment-portal:<sha> .` | Gated. **Not executed today** — see §6 | Skipped on red |
| 11 | **Deployment** | — | Gated. **Not implemented** — see §6 | Skipped on red |

Stages 7 and 8 are the ones this document exists for; the rest were already in the pipeline and are
described in [ci operations](../operations/jenkins-ci.md).

### Why "Start Test Environment" is its own stage

Because the browser suite no longer starts its own application. The suite drives a deployment —
the packaged JAR, started the way an operator starts it, against an external MySQL
([E2E testing](e2e-testing.md) §2). That is what makes the stage an assembly test of the artefact
that ships.

Splitting start-up from execution also makes a failure legible. "The application never became
healthy" and "the application is healthy but the journey is broken" are different defects with the
same red build if the two are one stage. Here the first stops at stage 7 and the second stops at
stage 8.

## 3. The reusable scripts

Every operational sequence lives in `scripts\ci\`, not in the `Jenkinsfile`. The pipeline calls six
scripts; a developer can run the same six by hand.

| Script | Responsibility | Exit code |
| --- | --- | --- |
| `env.cmd` | The shared defaults — the port, the database coordinates, the seeded accounts — sourced by the four scripts that need them. Not a lifecycle step; never run on its own | — |
| `start-database.cmd` | `docker compose … up -d --wait` on MySQL 8.4; prints container logs and state if it does not become healthy | 1 on an unhealthy database |
| `start-application.cmd` | Launches the packaged JAR detached, with stdout/stderr redirected to `target\ci-application.log` and `target\ci-application.err.log`, and writes the JVM's pid to `target\ci-application.pid` | 1 if the JAR is missing or the process cannot start |
| `wait-for-health.cmd` | Polls `/actuator/health` until it answers with a Spring health document whose status is `UP`, or the deadline passes; on timeout prints the tail of both log files | 1 on timeout |
| `run-e2e.cmd` | Runs the `e2e-tests` Failsafe execution against `E2E_BASE_URL` | 1 if the suite did not pass |
| `collect-diagnostics.cmd` | Copies the logs, the E2E failure artifacts and the Surefire/Failsafe XML into `target\ci-diagnostics\` | Always 0 |
| `stop-environment.cmd` | Stops the JVM (by pid, then by command-line match) and `docker compose … down -v` | Always 0 |

`scripts\dev\run-e2e.cmd` is a thin orchestrator over the six lifecycle scripts, so a local run and a
CI run cannot drift into testing different things.

**The defaults live in one file.** Jenkins runs each `bat` step in its own `cmd.exe`, so nothing is
inherited from one stage to the next; four scripts each defaulting the port themselves is four
defaults that will disagree the first time one is edited, and `start-application.cmd` binding one port
while `wait-for-health.cmd` polls another reads as "the application never started". `env.cmd` is
sourced with `call "%~dp0env.cmd"` after each script's own `setlocal`, and assigns a variable only
when it is not already defined, so the pipeline or an operator can still override any of them.

### Where the environment's configuration lives

`application.yml` reads every setting from an environment variable, and `start-application.cmd`
supplies them. Nothing is written twice, and the application's own defaults are untouched.

| Setting | Value in the test environment | Why |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` | Activates `DevelopmentUserSeeder` and nothing else |
| `APP_SEED_ENABLED` | `true` | The seeder's second gate (the third is a per-account variable) |
| `APP_SEED_*_EMAIL` / `APP_SEED_*_PASSWORD` | the throwaway values `BaseE2ETest` uses | The suite signs in as a real student and a real reviewer |
| `DB_URL` | `jdbc:mysql://127.0.0.1:<port>/assignment_portal` | The compose database |
| `UPLOAD_STORAGE_PATH` | `target\ci-uploads` | Inside the workspace, so `cleanWs()` reclaims it |
| `APP_PORT` | `8081` | What `E2E_BASE_URL` points at. **Not 8080** — Jenkins owns that port on the host running this pipeline, and a deployment that cannot bind it lets the readiness gate pass against Jenkins itself (§8) |

Two things about the credentials are worth stating plainly rather than leaving to be inferred:

- **They are not secrets.** They belong to a database that `start-database.cmd` creates and
  `stop-environment.cmd` destroys within the same run, published on the loopback interface only.
  `SECURITY.md` §5 and [test data](test-data.md) both record the rule: a credential for a
  database that is created and destroyed by the run is a throwaway, not a secret. No environment
  that holds real data ever sets the seeding variables that would use it.
- **A real credential never appears here.** The moment a stage needs one — a registry login, an
  SSH key for provisioning — it comes from the Jenkins credentials store and is bound into that
  stage's environment only ([CLAUDE.md §8](../../CLAUDE.md)). The pipeline needs none to run, which
  is exactly why it can be run by anyone.

### Why the E2E invocation names a Failsafe execution

```bat
call .\mvnw.cmd -B -Pe2e test-compile failsafe:integration-test@e2e-tests failsafe:verify@e2e-tests
```

The `e2e` profile adds a Failsafe execution whose id is `e2e-tests`; naming it with `@` runs that
execution alone. A bare `verify -Pe2e` also re-runs the integration suite — the same tests, already
run and already published by their own stage, now attributed to a second one. `test-compile` is
named because invoking a goal directly does not run the lifecycle, and Failsafe still needs compiled
test classes.

## 4. What is published, and when

| Artefact | Path | Attached to a failed build? |
| --- | --- | --- |
| Unit test results | `**/target/surefire-reports/*.xml` | Yes — `junit` runs in the stage *and* in `post { always }` |
| Integration test results | `**/target/failsafe-reports/*.xml` | Yes |
| E2E test results | `**/target/failsafe-reports/*.xml` (the `e2e-tests` execution) | Yes |
| E2E failure screenshots | `target/e2e-artifacts/**` | Yes — screenshot, page source and metadata per failed test |
| Application log | `target/ci-application.log`, `target/ci-application.err.log` | Yes — archived on `failure`, and inside the diagnostics bundle |
| Diagnostics bundle | `target/ci-diagnostics/**` | Yes — logs, failure artifacts and report copies in one directory |
| The runnable JAR | `target/*.jar` | Yes, when the package stage reached it — fingerprinted |

**Reports are published twice, deliberately.** The `Publish Reports and Screenshots` stage covers the
ordinary case: a test failed, `catchError` kept the pipeline alive, and the report is attached. The
`post { always }` block covers the case the stage cannot reach — a failure in Checkout, Validate or
Start Test Environment, where the pipeline never arrives at stage 9 at all.

**Application logs are collected on success too.** A green run's log is the baseline that makes a
startup-time regression visible, and it is the evidence `docs/evidence/` wants.

**Collection never fails the build.** `collect-diagnostics.cmd` and `stop-environment.cmd` always
exit 0. They run in `post`, where a second failure would replace the one the pipeline is already
reporting. A missing log is reported on the console and the script continues.

## 5. Cleanup

`post { always }` runs three steps in this order, and the order is load-bearing:

1. `stop-environment.cmd` — the JVM is stopped **first**, so its log is closed and complete before
   anything reads it, and so the application's port is free for the next build on this agent. It stops the process
   recorded in `target\ci-application.pid` and, as a fallback, any `java.exe` whose command line
   mentions the JAR — a pid file that was never written is exactly the crash-at-startup case.
2. `collect-diagnostics.cmd` — everything worth keeping is copied into `target\ci-diagnostics\`.
3. `cleanWs()` — the workspace is emptied, **last**, because every step above reads it.

`docker compose … down -v` destroys the volume. That is the point: a CI database that survived the
run would carry one build's submissions into the next, and the browser suite's fixed submission
titles would then match more than one row. A run must not depend on the run before it.

## 6. What is not implemented, and why

| Not implemented | Why |
| --- | --- |
| **The Dockerfile, and therefore a real Docker Build** | `CLAUDE.md` §13 puts the multi-stage Dockerfile in Milestone 5 and holds that milestone back until a `clean verify` is green. That build is not green. The stage exists, is gated, and is guarded by `fileExists('Dockerfile')`, so it is **skipped visibly** today rather than passing while doing nothing — and it activates by itself the day the file lands. `CLAUDE.md` §1 requires following the handbook over a conflicting request, and saying so: this is that case. |
| **Deployment** | Same milestone. Ansible provisioning, the health check after it, and the rollback path. The stage is present because the gate is a statement about *this* stage — it is skipped for exactly the reason Docker Build is, and the defect drill observes both. |
| **A registry push** | Pushing to a registry this pipeline has no credentials for, and no business holding, is how an image escapes review. Publishing is part of the delivery pipeline. |
| **Coverage gates, notifications** | Not requested; adding a threshold with nothing behind it is a gate that can only ever be tuned until it passes. |

## 7. Running it

### On Jenkins

Nothing is configured in Jenkins beyond the job itself ([ci operations](../operations/jenkins-ci.md)
§8). The pipeline is defined entirely by the committed `Jenkinsfile`, so a change to CI is a change
to a file that goes through review like any other.

### By hand, on the same machine

```bat
rem The whole environment lifecycle, ending in the browser suite and a teardown.
scripts\dev\run-e2e.cmd

rem Or individually, in order — each is independent and reports its own failure.
scripts\ci\start-database.cmd
scripts\ci\start-application.cmd
scripts\ci\wait-for-health.cmd
scripts\ci\run-e2e.cmd
scripts\ci\collect-diagnostics.cmd
scripts\ci\stop-environment.cmd
```

`scripts\dev\run-e2e.cmd` is the recommended entry point: it builds the JAR, runs the six steps,
tears the environment down whether or not the suite passed, and collects diagnostics.

### Where to look when it is red

| Symptom | First place to look |
| --- | --- |
| `wait-for-health.cmd` timed out | Its own console output — it prints the tail of both application logs |
| `wait-for-health` passed, the suite failed | `target\e2e-artifacts\<test>/{screenshot.png, page-source.html, metadata.txt}` |
| The suite could not reach the browser | `Selenium E2E` console output — Selenium Manager downloads the driver on first use and needs network access |
| The database never became healthy | `start-database.cmd` prints `docker compose ps` and 50 lines of container log |

## 8. Known gaps

Stated plainly, because this document is a claim about a pipeline and the claim has not been tested.

### What the first end-to-end run found

The browser suite has now executed once, by hand, on 2026-10-05: **4 tests, 4 errors**, every one of
them `TimeoutException … no such element: #email` at `LoginPage.submitCredentials`.

The cause was neither Selenium nor the suite. The Maven command was run directly, without
`start-application.cmd`, so no deployment existed — and `E2E_BASE_URL` defaulted to
`http://localhost:8080`, where **Jenkins** was listening. The captured page source
(`target/e2e-artifacts/*/page-source.html`) says everything: `<title>Sign in - Jenkins</title>`. The
browser drove Jenkins' own sign-in form. Three defects came out of that one run, all now fixed:

1. **The port collided.** `APP_PORT` defaulted to 8080 in four separate scripts — the port Jenkins
   itself uses, on the machine that runs the pipeline. Moved to 8081 and consolidated into `env.cmd`.
2. **The readiness gate could be satisfied by the wrong server.** `wait-for-health.cmd` accepted any
   `200`, and Jenkins answers on that port, so the gate passed against the wrong service. It now
   requires a Spring health document reporting `UP`. This was the dangerous one: a gate a foreign
   service can satisfy is a false green, which reports success for a deployment never tested.
3. **The suite reported a selector instead of a page.** It now fails naming the URL, the title and the
   form count, so the next occurrence is one line rather than a captured DOM and a guess.

| Gap | What would close it |
| --- | --- |
| **The pipeline has never run.** | Run it once against a real `windows` agent and record the outcome under [`docs/evidence/`](../evidence/README.md). |
| **The `@e2e-tests` invocation is unverified.** | Naming a Failsafe execution with `@` is standard Maven, but this project has not executed it. The first real run either passes the stage or reports `Unknown execution id`, which is unambiguous and cheap to fix. |
| **The scripts are unverified.** | They have been read, not run. The one execution so far went *around* them — the Maven command was run directly, which is exactly what let a port collision reach the browser. The first run through them is their test: `scripts\dev\run-e2e.cmd` locally, or the pipeline on an agent. |
| **`cleanWs()` on a Windows agent with a detached JVM.** | `stop-environment.cmd` stops the JVM before `cleanWs()` runs. If a stray process still holds a file lock, `cleanWs()` will report it — which is why the pid file exists rather than a `taskkill` by image name. |
| **The environment is shared by all four journeys.** | The suite is isolated by using distinct data (unique submission titles) and the database is destroyed between runs, so a run does not depend on the one before it. Two jobs sharing one agent would — which is what `disableConcurrentBuilds()` prevents. |
| **No recorded green build to gate.** | The last recorded green `clean verify` is Milestone 2's ([toolchain](../operations/toolchain.md) §9). The gate cannot be shown to *pass* a good revision until there is one; the [defect drill](../evidence/defect-demonstration.md) is the scenario that demonstrates the other direction. |
