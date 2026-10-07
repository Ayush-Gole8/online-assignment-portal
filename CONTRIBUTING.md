# Contributing

Thank you for working on the Online Assignment Portal. This document defines **how a change
travels from a local branch to `develop`**. The technical rules for the code itself live in
[`CLAUDE.md`](CLAUDE.md); read that first.

---

## 1. Ground rules

- `develop` is protected. **Direct pushes are forbidden.** Every change arrives through a pull
  request.
- Every change must be reviewable in one sitting. If a PR needs a summary longer than a
  screen, split it.
- No change is merged with a red pipeline. Jenkins CI must be green; the Selenium stage joins it
  when the end-to-end suite exists. A red build is **fixed**, never re-run until it happens to pass.
- Never commit a secret. See [`SECURITY.md`](SECURITY.md).
- Documentation and code move together. A behavioural change that updates no document is an
  incomplete change.

## 2. Branching

Branch from an up-to-date `develop` using a type prefix:

| Prefix | Use for | Example |
| --- | --- | --- |
| `feature/` | New user-visible capability | `feature/submission-upload` |
| `fix/` | Defect repair | `fix/csrf-token-on-upload-form` |
| `docs/` | Documentation only | `docs/adr-006-compose` |
| `chore/` | Build, CI, tooling, dependencies | `chore/pin-mysql-8.4` |
| `refactor/` | Behaviour-preserving restructuring | `refactor/extract-storage-service` |

Use lowercase kebab-case after the prefix. Keep branches short-lived; rebase on `develop`
rather than letting them drift.

```bash
git switch develop
git pull --ff-only
git switch -c feature/submission-upload
```

### Releases

A release is a **git tag**, `v` followed by three dot-separated numbers — `v1.0.1`, never `1.0.1`,
`v1.0`, or `latest`. The tag is authoritative: it is what determines the image version, what the
running application reports at `/actuator/info`, and what a deployment names. No build parameter
can introduce a version, and `latest` is never produced.

```bash
git switch develop
git pull --ff-only
git tag -a v1.0.1 -m "1.0.1"
git push origin v1.0.1
```

Tag a commit that has already passed CI. Building the tag runs the full pipeline and, when it is
green, publishes the image and deploys it — so the tag is a decision, not a bookmark. The rules for
which refs do what are in [`docs/operations/docker-cd.md`](docs/operations/docker-cd.md) §2.

## 3. Commits

Follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>(<scope>): <imperative summary>

<why the change is needed — not a restatement of the diff>
```

- **type** — `feat`, `fix`, `docs`, `test`, `build`, `ci`, `refactor`, `chore`, `perf`.
- **scope** — the feature package or area: `submission`, `storage`, `security`, `ci`, `docs`.
- **summary** — imperative mood, lowercase, no trailing period, under 72 characters.

The body explains **why**. The diff already explains **what**.

Atomic commits are preferred over one large commit, but a commit must leave the tree
buildable. Do not commit commented-out code or debugging output.

## 4. Pull requests

Open the PR against `develop` and fill in the repository template completely. A PR is ready to
merge only when:

1. The title follows the same Conventional Commit convention (it becomes the squash message).
2. The description states the **problem**, the **approach**, and the **verification** actually
   performed — including the exact commands run.
3. The self-review checklist in the template is ticked honestly.
4. Jenkins CI is green (see §5) and at least one reviewer has approved.
5. New or changed behaviour is covered by tests at the appropriate layer of the
   [test pyramid](docs/testing/strategy.md).

Reviewers: be specific and kind. Comment on the code, not the author. Distinguish
*blocking* from *nice-to-have*. If a PR changes an architectural decision, it must add or
supersede an ADR under [`docs/adr/`](docs/adr/) rather than editing history silently.

## 5. Continuous integration

Every pull request runs the Jenkins pipeline defined by [`Jenkinsfile`](Jenkinsfile). It is the
check that decides whether a change may merge, and it is not optional.

```
feature branch  ──▶  pull request  ──▶  Jenkins CI  ──▶  review  ──▶  develop
                          │                  │
                          │                  └─ red: push a fix to the branch, CI re-runs
                          └─ the PR is what Jenkins builds and what gets reviewed
```

The pipeline is one job with named stages:

| Stage | What it does |
| --- | --- |
| **Checkout** | Fetches the exact revision under test |
| **Validate** | Prints the agent's toolchain (`java`, `git`, `docker`, `docker compose`) so the environment is visible |
| **Unit Tests** | `.\mvnw.cmd -B clean test` — the fast suite |
| **Integration Tests** | `.\mvnw.cmd -B failsafe:integration-test failsafe:verify` — Testcontainers MySQL 8.4 |
| **Package** | `.\mvnw.cmd -B package -DskipTests` — the executable JAR |
| **Selenium E2E** | A real browser completes the student and reviewer journeys against a deployment the pipeline starts |
| **Publish Reports and Screenshots** | Surefire and Failsafe JUnit XML, the failure screenshots, and the JAR, attached to the build |
| **Release Version** | Whether this revision is a release, and which version — the tag on `HEAD`, or nothing |
| **Docker Build** | Packages the JAR into the image in `Dockerfile`, with the version baked in |
| **Publish Image** | *Release only.* Re-tags into `IMAGE_REPOSITORY:<version>` and pushes to Docker Hub |
| **Deploy** | *Release only, when `DEPLOY_ENV` names an environment.* The versioned image runs on the host, answers health, passes a smoke test — or the previous version is restored |

What follows from that:

- **The build fails when a test fails.** A red build is never merged and never bypassed by a re-run
  that has not addressed the cause.
- The pipeline runs on a **Windows agent labelled `windows`**, through the **Maven Wrapper** — never
  a Maven installed on `PATH`.
- **A pull request builds and tests only.** The three stages after **Release Version** are gated on
  the revision being a `vX.Y.Z` tag, so a feature branch reaches neither the registry nor a host.
  Publishing and deploying are things you do by tagging, deliberately — never a side effect of
  pushing a branch.
- **No credential is written in the `Jenkinsfile`.** An untagged build needs none: the integration
  suite starts its own MySQL container with credentials Testcontainers generates. On a release, two
  credentials are bound from the Jenkins credentials store — the Docker Hub token and the SSH key —
  each into the environment of one stage only (`docs/operations/jenkins-ci.md` §6).
- Reproduce CI locally with `.\mvnw.cmd clean verify` **before** pushing. A change that is green
  locally and red in CI is a difference worth understanding, not a flake to re-run.

Setup, agent requirements, and the full configuration are in
[`docs/operations/jenkins-ci.md`](docs/operations/jenkins-ci.md). The release rules, the host
contract, the smoke test and the rollback are in
[`docs/operations/docker-cd.md`](docs/operations/docker-cd.md).

## 6. Definition of done

A change is done when **all** of the following hold:

- [ ] It satisfies a requirement in [`docs/requirements/mvp.md`](docs/requirements/mvp.md),
      or a documented follow-up.
- [ ] It respects the dependency direction in
      [`docs/architecture/application-structure.md`](docs/architecture/application-structure.md).
- [ ] Tests exist at the correct layer and pass locally.
- [ ] `./mvnw clean verify` passes locally.
- [ ] No secret, credential, or environment-specific value is committed.
- [ ] Documentation, ADRs, and the toolchain record are updated where affected.
- [ ] The Jenkins CI pipeline is green.

## 7. Local development workflow

The loop a contributor runs before opening a pull request:

```bash
# Start the dependencies (MySQL) the application needs
docker compose up -d db

# Build, and run the unit and integration suites — what CI will run
./mvnw clean verify

# Run the application against the local database
./mvnw spring-boot:run
```

On Windows PowerShell or `cmd.exe`, replace `./mvnw` with `.\mvnw.cmd`. `clean verify` requires a
running Docker daemon: the integration suite starts a real MySQL 8.4 container rather than an
in-memory substitute. The environment variables each command needs are documented in
[`.env.example`](.env.example) and [`docs/operations/`](docs/operations/).

## 8. Reporting problems

- **Bugs and feature requests** — use the issue templates under `.github/ISSUE_TEMPLATE/`.
- **A red Jenkins build** that is not an obvious fix on the branch — use the **CI failure** issue
  template, which records the build, the failing stage, and whether it reproduces locally.
- **Security vulnerabilities** — do **not** open a public issue. Follow
  [`SECURITY.md`](SECURITY.md).
