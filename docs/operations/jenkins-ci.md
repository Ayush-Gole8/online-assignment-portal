# Jenkins Continuous Integration

**Status:** The pipeline is committed and **has not been executed on a Jenkins agent**. Every
statement below describes the definition, not an observed run. The release and deployment stages
added by the delivery work are described in full in [`docker-cd.md`](docker-cd.md), which carries
its own unverified-claims table.
**Related:** [CI quality gates](../testing/ci-quality-gates.md) · [CLAUDE.md §9](../../CLAUDE.md) ·
[Contribution workflow](../../CONTRIBUTING.md) · [Test strategy](../testing/strategy.md) ·
[E2E testing](../testing/e2e-testing.md) · [Docker CD](docker-cd.md) · [Toolchain](toolchain.md)

---

## 1. What this covers

[`Jenkinsfile`](../../Jenkinsfile) at the repository root is the pipeline definition. It answers
two questions, and the second one only on a tagged revision: **does this revision build and pass
its tests on a machine other than the author's?** and **is this revision a release, and where does
it go?**

The pipeline builds, tests — including the Selenium suite, against a deployment it starts itself —
packages, and, when the revision carries a `vX.Y.Z` tag and the build is green, publishes a
versioned image to Docker Hub and deploys it. Which of those happens is decided by the **ref**
being built, and by nothing else — the rules are a table in
[`docker-cd.md` §2](docker-cd.md) and are restated as a comment at the top of the `Jenkinsfile`.

[`docker-cd.md`](docker-cd.md) is the authority on the release half: the versioning rule, the
credentials, the host contract, the smoke test and the rollback. This document is the authority on
the pipeline as a whole — the stages, the agent, and the gate.

The gate those stages enforce is documented separately, because it is the part worth reading twice:
[`ci-quality-gates.md`](../testing/ci-quality-gates.md).

## 2. The pipeline at a glance

| Stage | Command | What it establishes | Fails the build? |
| --- | --- | --- | --- |
| **Checkout** | `checkout scm` | The exact revision under test is the one that was pushed | Yes |
| **Validate** | `java -version`, `git --version`, `docker version`, `docker compose version` | The agent has the toolchain, and which versions they are | Yes |
| **Build** | `.\mvnw.cmd -B clean test-compile` | Both source sets compile | Yes |
| **Unit Tests** | `.\mvnw.cmd -B test` | The fast suite — Surefire over `*Test`, tagged `unit` | Yes |
| **Integration Tests** | `.\mvnw.cmd -B failsafe:integration-test failsafe:verify` | The `*IT` suite against a real MySQL 8.4 container | Yes |
| **Package** | `.\mvnw.cmd -B package -DskipTests` | The executable Spring Boot JAR — the artefact the next two stages use | Yes |
| **Start Test Environment** | `scripts\ci\start-database.cmd`, `start-application.cmd`, `wait-for-health.cmd` | MySQL 8.4 is healthy, the packaged JAR is running, and it answers `/actuator/health` | Yes |
| **Selenium E2E** | `scripts\ci\run-e2e.cmd` | A real browser completes the student and reviewer journeys against that deployment | Yes |
| **Publish Reports and Screenshots** | `junit …`, `archiveArtifacts …` | Unit, integration and browser results, and the failure screenshots, attached to the build | No — publishes |
| **Release Version** | `scripts\ops\release-version.cmd` | Whether this revision is a release, and which version: the tag on `HEAD`, or nothing | Yes — an argument the pipeline cannot resolve is not a reason to continue quietly |
| **Docker Build** | `docker build --build-arg APP_VERSION=${IMAGE_TAG} -t ${LOCAL_IMAGE}:${IMAGE_TAG} .` | Packages the JAR the Package stage produced into the image in `Dockerfile`, and bakes the version into it. Gated on a green build. Always runs; tags locally | Skipped when ungreen |
| **Publish Image** | `scripts\ops\docker-login.cmd`, `publish-image.cmd` | The image is re-tagged into `IMAGE_REPOSITORY:<version>` and pushed to Docker Hub | Gated on green **and** `IS_RELEASE` |
| **Deploy** | `scripts\ops\deploy-release.cmd` | The versioned image runs on the target host, answers health over the network, and passes the smoke test — or the previous version is restored | Gated on green **and** `IS_RELEASE` **and** `DEPLOY_ENV != none` |

`post { always }` then stops the environment, collects diagnostics, publishes the reports a second
time, archives the JAR, the diagnostics bundle and the release record, and cleans the workspace.

The pipeline runs `clean verify`'s work spread across the stages above, rather than the single
`.\mvnw.cmd -B clean verify` command, so that a failure is attributed to the layer that produced it.
Both forms are equivalent in what they prove ([CLAUDE.md §10](../../CLAUDE.md)).

### The quality gate

Every stage from **Build** to **Selenium E2E** is wrapped in
`catchError(buildResult: 'FAILURE', stageResult: 'FAILURE')`: a failure sets the build result
immediately and lets the pipeline continue, so the reporting stage still runs and the report
explaining the failure is attached to the build that failed.

**Docker Build**, **Publish Image** and **Deploy** each carry
`when { expression { currentBuild.currentResult == 'SUCCESS' } }` as their first condition. A stage
that failed has already made that expression false, so none of their steps is evaluated at all: no
image is built, nothing is published, and nothing is deployed. The gate is structural, not
conventional — [`ci-quality-gates.md`](../testing/ci-quality-gates.md) §1 states it in full, and
[`defect-demonstration.md`](../evidence/defect-demonstration.md) is the drill that exercises it.

Each gate is stated in full in its own `when` block rather than relying on the previous stage
having applied one, so moving a stage cannot silently disable a gate. `Publish Image` and `Deploy`
add `env.IS_RELEASE == 'true'`, which is what "do not deploy untagged feature builds" means in
practice: no `vX.Y.Z` tag, no published image, and therefore nothing for a deployment to pull.

### Why `failsafe:integration-test` and `failsafe:verify` are both listed

They are not redundant. The `integration-test` goal runs the tests but never fails the build on
their result; `verify` is the goal that inspects the results and fails the build. Invoking either
one alone produces a pipeline that runs integration tests without ever being governed by them.

### Why the release version travels as a file

Jenkins runs every `bat` step in its own `cmd.exe`, so a variable set by one script does not
survive into the next stage — and `setlocal` inside a script means it does not survive the script
either. `release-version.cmd` therefore writes `target/release-version.txt`, and the **Release
Version** stage reads it and assigns `env.RELEASE_VERSION` and `env.IMAGE_TAG` itself. Absence of
the file is the negative answer: a revision with no release tag writes nothing, which is why the
stage treats "no file" as "not a release" rather than as an error.

## 3. The agent

The pipeline targets the Jenkins agent label **`windows`**:

```groovy
agent { label 'windows' }
```

An agent carrying that label must provide:

| Requirement | Why | Recorded in |
| --- | --- | --- |
| A **JDK 21** on `JAVA_HOME` | The build enforces `[21,22)` and fails immediately on the wrong JDK | [toolchain.md §2](toolchain.md) |
| **Git** on `PATH` | `checkout scm` and the Validate stage | [toolchain.md §5](toolchain.md) |
| A running **Docker Engine** with the **Compose v2** plugin | Testcontainers starts a MySQL 8.4 container for the integration suite, and the *Start Test Environment* stage starts another for the browser suite; the pipeline cannot pass without it | [toolchain.md §4](toolchain.md) |
| **Google Chrome** | The browser suite drives it headlessly. Selenium Manager downloads the matching driver on first use, so Chrome itself must be installed and the agent needs network access to the driver repository | [toolchain.md §6](toolchain.md) |
| The **Maven Wrapper** | Committed to the repository (Maven 3.9.16, `distributionType=only-script`), so no Maven install is required — but the agent needs network access to Maven Central on its first build to download Maven and the dependencies | [toolchain.md §2](toolchain.md) |
| **Git tags fetched into the job** | The **Release Version** stage reads the tag on `HEAD`. Without tag discovery every commit reports as unreleased and nothing is ever published | [docker-cd.md §4](docker-cd.md) |
| **`ssh`, `scp`, and an `ssh-agent` Jenkins can start** | The **Deploy** stage binds the deployment key with `sshagent(credentials: […])`; the deploy script never sees it | [docker-cd.md §4](docker-cd.md) |
| **The deployment host's key in the agent's `known_hosts`** | Host key verification is **not** disabled, and must not be. An unprovisioned agent fails the **Deploy** stage with `Host key verification failed` | [docker-cd.md §4](docker-cd.md) |
| A route to the deployment host, and to Docker Hub — on release builds only | The health check in the **Deploy** stage polls over the network from the agent; **Publish Image** pushes to Docker Hub. Neither is needed for a feature branch, which reaches neither stage | [docker-cd.md §3](docker-cd.md) |

`JAVA_HOME` is the JDK **root**, not its `bin` directory. This is the single most common cause of a
red first build; `mvn -version` prints the JDK actually in use.

Jenkins itself needs the following plugins, all of which the stages above depend on:

| Plugin | Used by |
| --- | --- |
| Pipeline (with Declarative) | The whole file |
| Git / GitHub Branch Source | `checkout scm`, multibranch scanning |
| Timestamper | `timestamps()` |
| JUnit | `junit` in **Publish Test Reports** |
| Workspace Cleanup | `cleanWs()` in `post` |
| Credentials Binding | `withCredentials` in **Publish Image** (the Docker Hub token) |
| SSH Agent | `sshagent` in **Deploy** (the deployment key) |

## 4. Pipeline options

| Option | Value | Purpose |
| --- | --- | --- |
| `timestamps()` | — | Every console line carries wall-clock time |
| `timeout(...)` | 60 minutes | A hung build frees the agent instead of holding it |
| `disableConcurrentBuilds()` | — | Two runs on one agent would race for the Testcontainers MySQL port and the stored-file volume |
| `buildDiscarder(logRotator(numToKeepStr: '10'))` | last 10 | Bounds disk use; older consoles are discarded |
| `skipDefaultCheckout()` | — | The **Checkout** stage performs the checkout, so the SCM definition has one home |
| `cleanWs()` | in `post { always }` | Leaves the workspace clean for the next build; Windows agents hold file locks, so leftovers break the build after |

## 5. Reports and artefacts

- **Publish Reports and Screenshots** attaches the Surefire (`target/surefire-reports/*.xml`) and
  Failsafe (`target/failsafe-reports/*.xml`) JUnit XML — the browser suite writes into the Failsafe
  directory too — to the build, where Jenkins renders pass/fail history and flaky-test trends.
  `allowEmptyResults: true` keeps the stage from failing when a suite produced no report; that
  situation is already a red build.
- **E2E failure screenshots** (`target/e2e-artifacts/**`) are archived. One directory per failed
  test, holding a screenshot, the server's page source, and the metadata that ties them to a test
  method ([E2E testing](../testing/e2e-testing.md) §5). Nothing is written on a green run, which is
  why the archive allows an empty result.
- **The diagnostics bundle** (`target/ci-diagnostics/**`) is archived. The application's logs, the
  failure artifacts and copies of the machine-readable reports in one directory, so a build
  discarded by the log rotator still has its evidence somewhere.
- **The runnable JAR** is archived and fingerprinted. The Boot plugin's sidecar, `*.jar.original`,
  is not matched by `target/*.jar`, so only the runnable JAR is retained. Fingerprinting records its
  SHA-256 so this exact binary can be traced to any downstream job that consumes it.
- **The release record** — `target/release-version.txt`, `target/deployment-report.txt`,
  `target/smoke-test.txt`, `target/ci-deploy.log` — is archived, each with `allowEmptyArchive`. A
  build that deployed nothing produces none of these files and is not the worse for it. This is
  what makes a failed deployment diagnosable after the workspace is gone: the report names the
  version, the environment, the previous version and the outcome, and the log holds the host's own
  output. See [`docker-cd.md`](docker-cd.md) §3 and §7.

The publishing is performed twice — in the stage, and again in `post { always }` — so that a failure
before the pipeline reaches the stage still attaches whatever exists.
[`ci-quality-gates.md`](../testing/ci-quality-gates.md) §4 has the full table.

## 6. Secrets

**No credential is written in the `Jenkinsfile`, and a build that is not a release needs none to
run.**

- The integration suite starts its **own** MySQL container through Testcontainers, with credentials
  Testcontainers generates for the run. It reads no database password from the environment.
- The integration test is handed a temporary storage root by the test base class, not a production
  path.
- A feature branch, `develop`, `main` and any other untagged ref run the same way, because the two
  stages that need a credential are both gated on the revision being a release. This is why CI can
  build and test the application without being trusted with a single production secret.

On a release, two credentials are bound — each into the environment of **one** stage, never inline,
never in a file, never in a build parameter ([CLAUDE.md §8](../../CLAUDE.md),
[SECURITY.md](../../SECURITY.md)):

| Jenkins credential ID | Type | Bound by | Bound into |
| --- | --- | --- | --- |
| `dockerhub-credentials` | Username with password | `withCredentials` | `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN`, in **Publish Image** only |
| `deploy-ssh-key` | SSH username with private key | `sshagent` | A temporary agent socket, in **Deploy** only — the pipeline never reads the key |

The identifiers are the *names* of entries in the credentials store; the values never leave it. Use
a **Docker Hub access token, not the account password** — revocable on its own, scoped to the
repositories it needs, and rotatable without changing the account's sign-in. `docker-login.cmd`
passes it to `docker login --password-stdin` over a pipe; it is never an argument, never an `echo |`
pipeline, and never a temporary file. The **Publish Image** stage logs out in its own
`post { always }`, because `docker login` writes the credential to the agent's
`~/.docker/config.json`, which outlives the workspace and therefore outlives `cleanWs()`.

Nothing that crosses from Jenkins to the deployment host is a credential. The only two values the
deploy script sends are the release version and the environment name — one of which is a public git
tag. The database password and the registry credential used for *pulling* live in the host's own
`.env`, which the pipeline never reads ([`docker-cd.md`](docker-cd.md) §5).

## 7. The branch and pull-request flow

```
feature branch  ──▶  pull request  ──▶  Jenkins CI  ──▶  review  ──▶  develop
                          │                  │
                          │                  └─ red: push a fix to the branch, CI re-runs
                          └─ the PR is the unit Jenkins builds and the unit that gets merged
```

1. **Feature branch.** Work happens on a `feature/`, `fix/`, `docs/`, `chore/` or `refactor/`
   branch cut from an up-to-date `develop` ([CONTRIBUTING.md §2](../../CONTRIBUTING.md)).
2. **Pull request.** The branch is pushed and a PR is opened **against `develop`**. The PR is what
   Jenkins builds — a red branch stays off `develop` entirely.
3. **Jenkins CI.** The pipeline runs the stages in §2. It is the check that decides whether the
   change may merge.
4. **Review.** A human approves. CI green and an approval are both required; neither substitutes for
   the other.
5. **develop.** The PR merges. `develop` is the protected integration branch; a change reaches it
   only through a green, reviewed pull request.

> **Branch protection is a GitHub setting, not a Jenkins one.** Jenkins reports; GitHub decides
> whether a red check blocks the merge. The pipeline is only as binding as the branch protection
> rule configured alongside it, and that rule must require the Jenkins check.

> **Naming note.** [`CONTRIBUTING.md`](../../CONTRIBUTING.md) and this document treat **`develop`**
> as the protected integration branch. [CLAUDE.md §9](../../CLAUDE.md) names `main` instead. The
> discrepancy is recorded rather than silently resolved — see *Decisions and Assumptions* in the
> change that introduced this pipeline, and reconcile the two documents before the branch model is
> relied upon by anyone else.

### The release flow

```
develop ──▶ tag vX.Y.Z ──▶ Jenkins builds the tag ──▶ green ──▶ image published ──▶ DEPLOY_ENV
                                                                   (Docker Hub)     staging | production | none
```

A release is a **tag on a commit that has already passed CI**, built as a tagged ref and given a
`DEPLOY_ENV`. The rules, and the reasons behind each row, are in
[`docker-cd.md` §2](docker-cd.md); the mechanics from the tag onwards are in §3 of the same
document.

Two things the flow above is deliberately shaped around:

- **The version is the tag and nothing else.** It cannot be supplied by a build parameter;
  `RELEASE_VERSION` exists only as an assertion that must agree with the tag, and the build fails
  when it does not. A pipeline that accepted a version from a form could publish a "release" that
  no tag ever named.
- **Publishing and deploying are separate decisions.** `DEPLOY_ENV=none` publishes the version and
  stops. That is the normal way to cut a release and deploy it later, deliberately, and it is the
  reason the deploy gate is a third condition rather than implied by the publish gate.

## 8. Setting up the job in Jenkins

1. Create a **Multibranch Pipeline** job (or a *Pipeline from SCM* pointed at `develop`).
2. Point the branch source at the GitHub repository and discover pull requests as well as branches,
   so a PR is built before it merges.
3. **Enable tag discovery** in the branch source, for tags matching `v*`. Without it the
   **Release Version** stage never sees a tag, every build reports as unreleased, and nothing is
   ever published or deployed — silently, because "this is not a release" is a normal and correct
   answer.
4. Leave the script path at its default (`Jenkinsfile` at the repository root).
5. Restrict the job to agents with the **`windows`** label.
6. Create the two credentials in §6 — `dockerhub-credentials` (a Docker Hub username and **access
   token**, with write access to the target repository) and `deploy-ssh-key` (the private key the
   deployment host accepts). Neither has a default anywhere in the repository.
7. Provision each `windows` agent's `known_hosts` with the deployment host's key, having **verified**
   it against the host's own fingerprint. The pipeline will not do this and does not disable the
   check ([`docker-cd.md` §4](docker-cd.md)).
8. In GitHub, add the resulting check to the branch-protection rule for `develop` and mark it
   **required**.

Nothing else is configured in Jenkins — the pipeline is defined entirely by the committed
`Jenkinsfile`, so a change to CI is a change to a file that goes through the same review as any
other ([CLAUDE.md §9](../../CLAUDE.md)).

### The build parameters

Five, declared in the `Jenkinsfile`. Only `DEPLOY_ENV` has a real default; the rest are empty on
purpose, because a guessed value for any of them produces a *successful* operation aimed at the
wrong place, which is the failure that is hardest to notice.

| Parameter | Default | Used for | Left empty |
| --- | --- | --- | --- |
| `DEPLOY_ENV` | `none` | Which environment the release is for: `staging`, `production`, or publish without deploying | Defaults to `none`, so an untagged or unconfigured build deploys nowhere |
| `IMAGE_REPOSITORY` | *(empty)* | The Docker Hub repository, as `<account>/<name>` | **Publish Image** fails the build with a message naming the parameter. A release with nowhere to publish is an error, not a skip |
| `DEPLOY_HOST` | *(empty)* | `user@host` of the deployment target | **Deploy** fails when `DEPLOY_ENV` names an environment and this is empty. The host is not defaulted anywhere in the repository |
| `DEPLOY_BASE_URL` | *(empty)* | The origin the smoke test requests | Derived from `DEPLOY_HOST` and `APP_HOST_PORT`, so the common case needs no parameter |
| `RELEASE_VERSION` | *(empty)* | **An assertion, not an input.** When set it must equal the version of the tag on `HEAD` | The tag alone decides. This parameter can never introduce a version |

## 9. What the pipeline deliberately does not do

| Not present | Why |
| --- | --- |
| **Deploy a revision that is not a tag** | The **Deploy** stage's `when` requires `IS_RELEASE`, which is true only when `HEAD` carries a `vX.Y.Z` tag. A feature branch cannot reach a deployment, and neither can `develop`. This is the "do not deploy untagged feature builds" rule, expressed as a gate rather than as a convention ([`docker-cd.md` §2](docker-cd.md)). |
| **Publish `latest`** | No code path in this repository writes that tag, and `publish-image.cmd` refuses the word outright. A deployment names the exact version it wants; "what is running" is answered by asking the deployment. ADR-006. |
| **Skip a stage silently on a release** | A release with no `IMAGE_REPOSITORY`, or with a `DEPLOY_ENV` and no `DEPLOY_HOST`, **fails** rather than being skipped. A green build that published nothing and deployed nothing is the one result a release must never report. |
| **Roll back on a red build that never reached the host** | There is nothing to undo, and the report says so rather than claiming to have rolled back. See [`docker-cd.md` §7](docker-cd.md). |
| **A coverage gate, notifications** | Not requested at this stage; adding them now would be a threshold with nothing behind it. A failed deployment is a red build and an archived report — nobody is paged. |
| **Provisioning a host** | The `Deploy` stage places the composition and the host-side script with `scp` and runs one script over `ssh`; that is a deployment, not provisioning. Turning a bare host into one that can receive a release is the [`ansible/`](../../ansible) playbook, run by hand before any release is deployed ([`ansible.md`](ansible.md)). No Jenkins stage invokes it, and none should: provisioning is a prerequisite of the pipeline, not a stage inside it. |

## 10. Known gaps

| Gap | Why it matters | How to close it |
| --- | --- | --- |
| **The pipeline has never run.** | Every claim in this document is about a file, not a build. The stages, the plugin requirements, and the artefact globs are reviewed, not verified. | Run it once against a real `windows` agent with a green revision and record the outcome under [`docs/evidence/`](../evidence/README.md). |
| **Nothing in the release or deploy stages has run.** | No image has been built by this pipeline, no push attempted, no host contacted, no rollback fired. [`docker-cd.md` §8](docker-cd.md) lists every unexercised claim one by one. | Cut a `vX.Y.Z` tag on a green revision and run the pipeline end to end against a staging host, recording the output. |
| **Branch protection is unverified.** | A green Jenkins check that is not *required* in GitHub blocks nothing. | Configure the required check and confirm the evidence row "A pull request gates on CI". |
| **No green build to demonstrate.** | The last recorded green `clean verify` is Milestone 2's ([`toolchain.md` §9](toolchain.md)). The pipeline inherits that gap — it cannot be shown to gate a passing revision until there is one, and CLAUDE.md §13's precondition for the delivery work is exactly this. | Land the pending Milestone 4 fixes, run `clean verify` green, then run the pipeline on that revision. |
| **The `develop`/`main` discrepancy above.** | Two documents name different protected branches. | Reconcile `CONTRIBUTING.md` (and this file) with [CLAUDE.md §9](../../CLAUDE.md), or supersede whichever is wrong, in a documentation change. |
| **The environment scripts have not been run.** | `scripts\ci\*.cmd` are read, not executed. They are the difference between a stage that starts a real environment and one that reports a failure the first time it is tried. | Run the pipeline once and fix what the first run reports. `scripts\dev\run-e2e.cmd` runs the identical steps locally, which is the cheaper way to find the same problems. |
| **The `@e2e-tests` Failsafe invocation is unverified.** | Naming a Failsafe execution with `@` is standard Maven, but this project has not executed it. A wrong execution id fails loudly and unambiguously (`Unknown execution id`), so the cost of being wrong is one red build. | The first pipeline run either passes the stage or names the execution it could not find. |
| **The agent needs a browser.** | The browser suite drives Chrome through Selenium Manager, which downloads the matching driver on first use. The agent therefore needs Chrome installed (or the manager able to fetch it) and network access to the driver repository. | Record the Chrome version the first run used in [toolchain.md](toolchain.md), which currently marks the browser row unverified. |
| **The Compose version on the agent is unverified for `--wait`.** | The **Deploy** stage's host-side script uses `docker compose up --wait`, which exists only in the Compose **v2** plugin. The Validate stage prints `docker compose version`, but the requirement is not recorded as a version in [toolchain.md](toolchain.md). | Record the Compose version the first release run used, and state the minimum in that document. |
