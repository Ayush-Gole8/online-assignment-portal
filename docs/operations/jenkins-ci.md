# Jenkins Continuous Integration

**Status:** The pipeline is committed and **has not been executed on a Jenkins agent**. Every
statement below describes the definition, not an observed run.
**Related:** [CLAUDE.md §9](../../CLAUDE.md) · [Contribution workflow](../../CONTRIBUTING.md) · [Test strategy](../testing/strategy.md) · [Toolchain](toolchain.md)

---

## 1. What this covers

[`Jenkinsfile`](../../Jenkinsfile) at the repository root is the continuous-integration definition.
It answers one question on every pull request: **does this revision build and pass its tests on a
machine other than the author's?**

The pipeline builds, tests and packages. It does **not** deploy the application and does **not**
publish a container image — those stages arrive with the delivery-pipeline milestone, and building
them now would ship a deployment path that has never seen a passing build
([CLAUDE.md §13](../../CLAUDE.md)). What exists here is the first half of the pipeline in
[`system-overview.md`](../architecture/system-overview.md) §5: into Jenkins, through Maven, through
the tests, out as a versioned artefact.

## 2. The pipeline at a glance

| Stage | Command | What it establishes | Fails the build? |
| --- | --- | --- | --- |
| **Checkout** | `checkout scm` | The exact revision under test is the one that was pushed | Yes |
| **Validate** | `java -version`, `git --version`, `docker version`, `docker compose version` | The agent has the toolchain, and which versions they are | Yes |
| **Unit Tests** | `.\mvnw.cmd -B clean test` | The fast suite — Surefire over `*Test`, tagged `unit` | Yes |
| **Integration Tests** | `.\mvnw.cmd -B failsafe:integration-test failsafe:verify` | The `*IT` suite against a real MySQL 8.4 container | Yes |
| **Package** | `.\mvnw.cmd -B package -DskipTests` | The executable Spring Boot JAR | Yes |
| **Publish Test Reports** | `junit …` | Surefire and Failsafe XML are attached to the build | No — publishes |
| **Archive Artifact** | `archiveArtifacts … fingerprint: true` | The JAR is retained and its checksum recorded | No — archives |

The pipeline runs `clean verify`'s work spread across the stages above, rather than the single
`.\mvnw.cmd -B clean verify` command, so that a failure is attributed to the layer that produced it.
Both forms are equivalent in what they prove ([CLAUDE.md §10](../../CLAUDE.md)).

### Why the test stages use `catchError`

A test failure must fail the build — that is non-negotiable. But a build that fails *before* the
reporting stages run leaves the failure with no report attached, which is the least useful red build
there is. Wrapping each test stage in `catchError(buildResult: 'FAILURE', stageResult: 'FAILURE')`
sets the build result to `FAILURE` immediately while allowing the pipeline to reach Publish Test
Reports and Archive Artifact. The build is red; the reason for it is still there to read.

### Why `failsafe:integration-test` and `failsafe:verify` are both listed

They are not redundant. The `integration-test` goal runs the tests but never fails the build on
their result; `verify` is the goal that inspects the results and fails the build. Invoking either
one alone produces a pipeline that runs integration tests without ever being governed by them.

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
| A running **Docker Engine** with the **Compose v2** plugin | Testcontainers starts a MySQL 8.4 container for the integration suite; `clean verify` cannot pass without it | [toolchain.md §4](toolchain.md) |
| The **Maven Wrapper** | Committed to the repository (Maven 3.9.16, `distributionType=only-script`), so no Maven install is required — but the agent needs network access to Maven Central on its first build to download Maven and the dependencies | [toolchain.md §2](toolchain.md) |

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
| Credentials Binding | The credential *mechanism* the pipeline assumes for later stages (nothing binds one yet) |

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

- **Publish Test Reports** attaches the Surefire (`target/surefire-reports/*.xml`) and Failsafe
  (`target/failsafe-reports/*.xml`) JUnit XML to the build, where Jenkins renders pass/fail history
  and flaky-test trends. `allowEmptyResults: true` keeps the stage from failing when a suite
  produced no report — that situation is already a red build.
- **Archive Artifact** retains `target/*.jar` and fingerprints it. The Boot plugin's sidecar,
  `*.jar.original`, is not matched by `*.jar`, so only the runnable JAR is archived. Fingerprinting
  records its SHA-256 so this exact binary can be traced to any downstream job that consumes it.

## 6. Secrets

**No credential is written in the `Jenkinsfile`, and the pipeline needs none to run.**

- The integration suite starts its **own** MySQL container through Testcontainers, with credentials
  Testcontainers generates for the run. It reads no database password from the environment.
- The integration test is handed a temporary storage root by the test base class, not a production
  path.
- This is why CI can build and test the application without being trusted with a single production
  secret. The moment a stage needs one — a registry login, an SSH key for provisioning — it must
  take it from the **Jenkins credentials store** and bind it into the environment of that stage
  only, never inline ([CLAUDE.md §8](../../CLAUDE.md), [SECURITY.md](../../SECURITY.md)).

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

## 8. Setting up the job in Jenkins

1. Create a **Multibranch Pipeline** job (or a *Pipeline from SCM* pointed at `develop`).
2. Point the branch source at the GitHub repository and discover pull requests as well as branches,
   so a PR is built before it merges.
3. Leave the script path at its default (`Jenkinsfile` at the repository root).
4. Restrict the job to agents with the **`windows`** label.
5. In GitHub, add the resulting check to the branch-protection rule for `develop` and mark it
   **required**.

Nothing else is configured in Jenkins — the pipeline is defined entirely by the committed
`Jenkinsfile`, so a change to CI is a change to a file that goes through the same review as any
other ([CLAUDE.md §9](../../CLAUDE.md)).

## 9. What the pipeline deliberately does not do

| Not present | Why |
| --- | --- |
| **Deploy** | The deployment, health-check and rollback stages are the delivery-pipeline milestone. A deploy step now would deploy code that has never had a green build. |
| **Build or push a Docker image** | Same milestone. There is no `Dockerfile` and no registry configuration yet. |
| **A Selenium stage** | Selenium is not a dependency and no `*E2E` test exists. The Maven `e2e` profile is wired and inert (`pom.xml`); the pipeline gains the stage when the suite does. |
| **Coverage gates, notifications** | Not requested at this stage; adding them now would be a threshold with nothing behind it. |

## 10. Known gaps

| Gap | Why it matters | How to close it |
| --- | --- | --- |
| **The pipeline has never run.** | Every claim in this document is about a file, not a build. The stages, the plugin requirements, and the artefact globs are reviewed, not verified. | Run it once against a real `windows` agent with a green revision and record the outcome under [`docs/evidence/`](../evidence/README.md). |
| **Branch protection is unverified.** | A green Jenkins check that is not *required* in GitHub blocks nothing. | Configure the required check and confirm the evidence row "A pull request gates on CI". |
| **No green build to demonstrate.** | The last recorded green `clean verify` is Milestone 2's ([`toolchain.md` §9](toolchain.md)). The pipeline inherits that gap — it cannot be shown to gate a passing revision until there is one. | Land the pending Milestone 4 fixes, run `clean verify` green, then run the pipeline on that revision. |
| **The `develop`/`main` discrepancy above.** | Two documents name different protected branches. | Reconcile `CONTRIBUTING.md` (and this file) with [CLAUDE.md §9](../../CLAUDE.md), or supersede whichever is wrong, in a documentation change. |
