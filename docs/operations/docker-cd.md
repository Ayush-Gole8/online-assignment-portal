# Docker CD — publishing a release and deploying it

## 1. What this document covers

The **delivery** half of the pipeline: what turns a tag into a running release.

[`docker.md`](docker.md) describes the image and the container lifecycle on one machine.
[`jenkins-ci.md`](jenkins-ci.md) describes continuous **integration** — the five stages that
prove a change before anything is published. This document describes what happens after that:
the version a build is, the registry it is published to, the host it reaches, the health check
after it, and the way back if any of it goes wrong.

The boundary is deliberate and worth stating. Integration ends when the suites are green and a
JAR exists. Delivery begins when a human decides this revision is a release — expressed as a git
tag — and ends when a smoke test has confirmed that the thing answering requests is the thing
that was released.

> **Status: written, not executed.** No image has been pushed and nothing has been deployed by
> this process. §8 lists every claim on this page that has not been exercised, one by one. The
> precondition CLAUDE.md §13 sets for the delivery work — a green `./mvnw clean verify` — is
> still unmet.

---

## 2. The release rules

What runs is decided by the **ref** being built, and by nothing else. This is the whole policy:

| ref | CI (build, unit, integration, E2E) | image build | publish to Docker Hub | deploy |
| --- | --- | --- | --- | --- |
| `feature/*`, `fix/*`, `docs/*`, `chore/*`, `refactor/*` | yes | yes, local only | no | no |
| `develop` | yes | yes, local only | no | no |
| tag `vX.Y.Z` | yes | yes | yes | if `DEPLOY_ENV` is not `none` |
| anything else (e.g. `main`) | yes | yes, local only | no | no |

Four consequences worth being explicit about:

- **Feature builds are never deployed, and never published.** A feature branch produces an image
  tagged with its commit, on the agent, for the length of the build. It cannot reach a registry
  and there is nothing on a host for it to reach: the two gates that would have to pass are both
  keyed on the revision being a release.
- **A tag is deployed only if somebody says so.** Publishing and deploying are separate decisions.
  `DEPLOY_ENV=none` publishes the release and stops, which is the normal way to cut a version and
  deploy it later, deliberately.
- **The git tag is the authoritative version.** Not a build parameter, not `pom.xml`, not
  `latest`. `v1.0.1` on the commit produces the image `1.0.1`, the `/actuator/info` version
  `1.0.1`, and the deployment's `APP_IMAGE_TAG=1.0.1` — one value, from one place.
- **`latest` is never produced.** There is no code path in this repository that writes it. The
  consequence is real and accepted: a deployment names the exact version it wants, and "what is
  running" is answered by asking the deployment, not by convention.

### What makes a revision a release

Exactly one thing: a git tag matching `v` followed by three dot-separated numbers. `v1.0.1` is a
release; `1.0.1` is not (the `v` is the convention this repository's tags follow, and accepting
both would mean two names for one release); `v1.0` is not; `v1.0.0-rc.1` is not. A near-miss is a
tag nobody agreed on, and it is refused rather than interpreted.

The tag is read with `git describe --tags --exact-match HEAD` — **exact-match**, not nearest. The
distinction is the entire question: without it every commit after `v1.0.0` on `develop` would
report `v1.0.0` and the pipeline would republish a release from ordinary work.

---

## 3. The release process, end to end

Eleven steps, in the order the pipeline performs them. The first four are the release; the rest
are the deployment, and steps 5–11 run only when the build is green **and** the revision is a tag
**and** `DEPLOY_ENV` is not `none`.

| # | Step | Where it happens | What it does |
| --- | --- | --- | --- |
| 1 | Identify the release version | `scripts/ops/release-version.cmd` | Reads the tag on this exact commit; writes `target/release-version.txt`, or writes nothing when there is no release tag |
| 2 | Authenticate to the registry | `scripts/ops/docker-login.cmd` | `docker login` to Docker Hub, token over stdin, credential bound by `withCredentials` |
| 3 | Build the image | `Dockerfile`, `Docker Build` stage | `docker build --build-arg APP_VERSION=<version>` — bakes the version into the artefact |
| 4 | Tag and publish the versioned image | `scripts/ops/publish-image.cmd` | Re-tags into `IMAGE_REPOSITORY:<version>` and pushes; refuses `latest` and malformed names |
| 5 | Connect to the deployment host | `scripts/ops/deploy-release.cmd` | SSH with the key held by `sshagent`; host key verification stays on |
| 6 | Pull the exact image version | `deploy/deploy-on-host.sh` | `docker compose pull app`, **before** anything is replaced |
| 7 | Update the deployment configuration | `deploy/deploy-on-host.sh` | Rewrites exactly two lines of the host's `.env` — `APP_IMAGE_TAG`, `APP_ENVIRONMENT` |
| 8 | Restart / update the containers | `deploy/deploy-on-host.sh` | `docker compose up -d --no-build --remove-orphans --wait app` |
| 9 | Wait for health | `scripts/ci/wait-for-health.cmd`, from the agent | Polls the deployment's `/actuator/health` over the network until it reports `UP` |
| 10 | Run the smoke test | `scripts/ops/smoke-test.ps1` | Five checks, including that the running release reports its own version |
| 11 | Report, and roll back on failure | `scripts/ops/deploy-release.cmd` | Writes `target/deployment-report.txt`; on a failed 8, 9 or 10, redeploys the previous version |

Everything above step 3 is the `Release Version` stage in the [`Jenkinsfile`](../../Jenkinsfile).
Each step is a script rather than an inline sequence, for one reason: a deployment that has gone
wrong is a deployment somebody has to read and re-run, and a sequence that exists only as a stage
definition cannot be read or re-run at all.

### Why step 1 is a file and not an environment variable

Jenkins runs every `bat` step in its own `cmd.exe`. A variable set by one script does not survive
into the next stage, and a variable set inside a script does not even survive the script's own
`setlocal`. The version therefore travels as `target/release-version.txt`, and the stage reads it
and assigns `env.RELEASE_VERSION` itself.

**Absence of the file is the negative answer.** A revision that is not a release produces no file,
which is why the stage treats "no file" as "not a release" rather than as an error — an error
there would make every ordinary branch build go red.

### Why step 6 comes before step 8

`docker compose pull app` runs while the previous container is still serving. That is the step
that proves the image exists. If the tag was never pushed, or was pushed under a different name,
the deployment fails **there** — with the old version still running — rather than after the old
container has been stopped and the new one turns out to be unavailable.

`--no-build` in step 8 is the point of the host-side script. It makes the deployment use the
image that was pushed, and fail rather than quietly build a replacement if no such image can be
pulled.

---

## 4. Credentials and agent prerequisites

### Jenkins credentials

Two entries, both referenced by **ID** and never by value (`scripts/ops/env.cmd`). The IDs are the
defaults; change them in one place, or override the environment variable.

| Jenkins credential ID | Type | Used by | Holds |
| --- | --- | --- | --- |
| `dockerhub-credentials` | Username with password | `Publish Image` stage | Docker Hub username, and an **access token** with write access to `IMAGE_REPOSITORY` |
| `deploy-ssh-key` | SSH username with private key | `Deploy` stage | Private key the deployment host accepts |

The values exist in exactly two places: the Jenkins credentials store, and — for the length of
one stage — the environment of a process `withCredentials` created. They are never in a file in
this repository, never in a build parameter, never in a console line, and never in an Ansible
variable (CLAUDE.md §7 rules 1 and 2).

Use a **Docker Hub access token, not the account password.** A token is revocable on its own,
scoped to read/write on the repositories it needs, and rotating it does not change the account's
sign-in. Losing a repository-scoped token is an inconvenience; losing the account password is not.

`docker-login.cmd` passes the token to `docker login --password-stdin`, piped from a PowerShell
process that reads it out of its own environment. It is not an argument (`-p` puts the value in
the process list, where every user on the agent can read it), not an `echo |` pipeline (which
leaves it in the shell's history and in the pipe buffer), and not a temporary file (which
survives the build unless something remembers to delete it).

The `Publish Image` stage's own `post { always }` runs `docker logout`, because `docker login`
leaves the credential in the agent's `~/.docker/config.json`, which outlives the workspace and
therefore outlives `cleanWs()`.

### Agent prerequisites

| Requirement | Why | Consequence of not meeting it |
| --- | --- | --- |
| Docker with the **Compose v2 plugin** | The host-side script uses `docker compose up --wait`; `--wait` does not exist in Compose v1's `docker-compose` | The deployment fails at step 8 with `unknown flag: --wait` |
| `ssh`, `scp`, and an `ssh-agent` Jenkins can start | `sshagent(credentials: [...])` provides the key; the deploy script never sees it | The `Deploy` stage fails before leaving the agent |
| **The deployment host's key in the agent's `known_hosts`** | Host key verification is **not** disabled — see below | `Host key verification failed`, on the first `ssh` |
| A route from the agent to the deployment host on the application port | Step 9 polls health over the network, from a different machine than the one running the container | Health never answers; the deployment rolls back |
| Git tags fetched into the job | Step 1 reads the tag on `HEAD` | Every commit reports as unreleased, and nothing is ever published |

**The host key must be provisioned, and the pipeline will not provision it.** There is no
`-o StrictHostKeyChecking=no` in this repository and none may be added. Accepting an unknown host
key on every connection is the same as having no host identity at all: it makes a man in the
middle indistinguishable from the deployment target, on the connection that is about to receive a
release. Provision the key out of band —

```bash
ssh-keyscan -H <deploy-host> >> ~/.ssh/known_hosts   # then VERIFY the key against the host's own fingerprint
```

— and treat "Host key verification failed" on a first run as the control working.

`ssh` additionally runs with `-o BatchMode=yes -o ConnectTimeout=15`. Batch mode makes it **fail
rather than prompt**: a prompt in a `bat` step has nobody to answer it, so without this the job
would sit until the stage timeout and report a timeout instead of the missing key or the
unverified host key that actually caused it.

---

## 5. The deployment host contract

A deployment host holds three things in `DEPLOY_PATH` (default `/opt/assignment-portal`):

| File | Placed by | Frequency |
| --- | --- | --- |
| `compose.prod.yaml` | `deploy-release.cmd`, over `scp` | **every release** |
| `deploy-on-host.sh` | `deploy-release.cmd`, over `scp` | **every release** |
| `.env` | A human, once, when the host is provisioned | never by the pipeline (except two lines) |

Both files are copied on **every** release rather than only when they have changed, so the host
runs the composition and the script that are in the tag being deployed — the only version of
either that has been reviewed alongside the code it is about to run. A host left holding last
month's compose file is a host whose behaviour nobody can predict from the repository.

Provision the host once, from the template:

```bash
mkdir -p /opt/assignment-portal
cp deploy/env.production.example /opt/assignment-portal/.env
# then replace every `change-me` ON THE HOST, and never commit the result
```

### `.env`: who owns which line

The split is the security boundary of this whole process, so it is drawn explicitly.

| Owner | Variables | Notes |
| --- | --- | --- |
| **The host** | `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `DB_ROOT_PASSWORD`, `IMAGE_REPOSITORY` | The deployment's secrets and its identity. **Never pass through Jenkins.** The pipeline never reads them. |
| **The host** (optional) | `APP_HOST_PORT`, `APP_BIND_ADDRESS`, `SESSION_TIMEOUT`, `SESSION_COOKIE_SECURE`, `SPRING_PROFILES_ACTIVE` | Each has the same default in `application.yml`; leaving them commented out is the normal case |
| **The pipeline** | `APP_IMAGE_TAG`, `APP_ENVIRONMENT` | `deploy-on-host.sh` rewrites exactly these two lines, and appends them if absent. It rewrites nothing else, so a credential placed in the file is not disturbed by a deployment |

`IMAGE_REPOSITORY` is deliberately host-owned rather than pipeline-supplied. It is a property of
where the image is published, not of any one release — and a host that could be told to pull from
an arbitrary repository by a build parameter is a host that can be pointed at an image nobody
reviewed. `publish-image.cmd` takes it from a parameter because it is the account publishing;
`compose.prod.yaml` reads it from the host's `.env` because that is the account pulling. They have
to agree, and a disagreement is a failed pull rather than a silent substitution.

**There is no `APP_SEED_*` variable, and the absence is a control.** `DevelopmentUserSeeder`
needs three conditions at once — the `dev` profile, `APP_SEED_ENABLED=true`, and a password for
the account. Two of the three are on this list only if somebody adds them. A deployment that
never mentions them cannot seed a demo account with a guessable password.

### What travels over the connection

The release version and the environment name. Both are public — one of them is a git tag. No
credential crosses from Jenkins to the host in either direction.

---

## 6. How the running application identifies its version

The deployment has to be able to answer "am I running what I released", and a deployment that
cannot is a deployment where "it is healthy" means only that *something* healthy is running. A
stale image left behind by a failed pull satisfies that perfectly.

The version is therefore **baked into the image at build time**:

```dockerfile
ARG APP_VERSION=unknown
ENV APP_VERSION=${APP_VERSION}
```

and the build passes it from the same variable as the image tag, in one command:

```
docker build --build-arg APP_VERSION=1.0.1 -t <repository>:1.0.1 .
```

The tag and the value come from one variable in one command, so an image cannot be tagged one
version and identify itself as another. A deployment that pulls `<repository>:1.0.1` gets an image
that reports `1.0.1` whatever its own configuration says.

The application publishes it at `/actuator/info`, alongside the environment it landed in:

```json
{ "app": { "version": "1.0.1", "environment": "production" } }
```

`smoke-test.ps1` step 10 asserts both. This is what makes a deployment falsifiable: a
configuration-supplied version would be a claim the deployment makes about itself, and a
deployment running the wrong image would make it just as confidently.

**The version is identity, and the environment is configuration.** They are treated differently
on purpose. The version travels with the artefact, so it cannot be contradicted. The environment
comes from `compose.prod.yaml`, so that one artefact can be promoted between environments without
being rebuilt (CLAUDE.md §8).

`/actuator/info` is served **anonymously**, and that is the trade this design accepts: a smoke
test that had to hold a credential to ask the question would have to store one on the machine it
is checking. The bound on the trade is that `info.*` is the one namespace where a careless key is
a disclosure rather than a mistake — `application.yml` says so at the declaration, and
`AuthorizationIT.publishesNothingButTheBuildIdentityFromTheInfoEndpoint` asserts it. `env`,
`configprops` and `beans` remain unexposed.

### The smoke test

Five checks, and each exists because a different one can pass while the deployment is still wrong:

| Check | Claim it establishes |
| --- | --- |
| `/actuator/health` reports `UP` | The application is running and its own dependencies answer |
| `/actuator/info` `app.version` equals the released version | **The release that was deployed is the release that is answering** |
| `/actuator/info` `app.environment` equals the target environment | The release was aimed at the host it landed on |
| `/login` answers 200 and renders an `email` field | A template rendered — not merely that a port is open |
| `/` answers 3xx for an anonymous visitor | The security filter chain is live |

It is four HTTP requests, not a second end-to-end suite; the pyramid stays a pyramid
(CLAUDE.md §6). It deliberately does **not** check that the database has the right rows, that an
upload works, or that a reviewer can approve — those are the browser suite's job on a deployment
the pipeline built itself, and a smoke test that duplicated them would be a slower, weaker copy
that needed credentials, which is the one thing a smoke test should never carry.

---

## 7. Rollback

**A rollback is not a separate operation; it is the second half of a failed deployment.** It is
handled inside `deploy-release.cmd`, on the failure paths, for three reasons:

1. **The target is read, not remembered.** `deploy-on-host.sh` prints `previous=<version>` on its
   first line, *before* it changes anything, and the agent reads that line out of the host's own
   output. The version to return to is what the host actually had thirty seconds ago.
2. **The system is left as it was found, without anyone having to notice.** By the time the stage
   returns, the host is serving the previous version. A red build that also left the host broken
   would be two problems.
3. **It is the same operation.** Rollback calls `deploy-on-host.sh deploy <previous> <env>` — the
   code path exercised on the happy path, so what runs when it matters is what runs normally.

It fires when the release *started* but did not **verify** — a failed health check or a failed
smoke test — and when the release did not start at all after the host had been reached.

It does **not** fire when the host was never reached: nothing was changed, so there is nothing to
undo, and the report says exactly that rather than claiming to have rolled back.

It does **not** fire on a first deployment. There is no previous version, and the script says so
rather than pretending. It also refuses to "roll back" a redeployment of the version that is
already running.

What a rollback does **not** do, and what it does instead:

| Not undone | Why | What to do |
| --- | --- | --- |
| The published image | The registry is a record; a release that was published stays published | Publish a new version. Never delete a tag somebody may have deployed |
| A database migration | Flyway is forward-only (ADR-003) | The rollback redeploys application code against a schema that is already migrated. A release whose migration is not backward-compatible needs a forward fix, and this is a real limitation, not a hidden one |
| Whatever the failed release already served | Traffic was live during the window | Rollback bounds the window; it does not erase it |

The three outcomes are distinguished in `target/deployment-report.txt`, because "it rolled back"
and "it rolled back and that also failed" are different incidents:

- `FAILED, ROLLED BACK TO <version>` — the expected outcome, and the host is healthy on the old release
- `FAILED, ROLLBACK TO <version> ALSO FAILED` — **needs a human.** The host is in an unknown state
- `FAILED, HOST UNREACHABLE - NOTHING CHANGED` / `FAILED, NOTHING TO ROLL BACK TO` — no action on the host was needed

---

## 8. Verification status

Every claim on this page that has **not** been executed, in one place. Nothing here has been run:
no image has been built by this pipeline, no push has been attempted, no host has been contacted,
and no rollback has fired.

| Claim | Status |
| --- | --- |
| `release-version.cmd` writes `1.0.1` for tag `v1.0.1`, and writes nothing for an untagged commit | **Written, not run** |
| `release-version.cmd` fails when `RELEASE_VERSION` disagrees with the tag | **Written, not run** |
| `docker-login.cmd` authenticates with the token over stdin | **Written, not run** |
| `publish-image.cmd` refuses `latest` and a malformed repository name | **Written, not run** |
| `docker build --build-arg APP_VERSION=…` bakes the version in | **Written, not run** (the `ARG`/`ENV` lines exist; the build that uses them has not run) |
| `/actuator/info` publishes `app.version` and `app.environment` | Asserted by `AuthorizationIT` — **written, not run** |
| `deploy-on-host.sh` rewrites exactly two lines and no others | **Written, not run** |
| `docker compose pull` before `up` | **Written, not run** |
| `docker compose up --wait` blocks until the healthcheck passes | **Written, not run**; `--wait` requires the Compose **v2 plugin**, which is not recorded in [`toolchain.md`](toolchain.md) |
| The `Deploy` stage's `when` gates skip a feature branch | **Written, not run**; the equivalent gates on `Docker Build` are observed to skip — `docs/evidence/defect-demonstration.md` |
| The rollback restores the previous version | **Written, not run** |
| The `Publish Image` and `Deploy` stages appear in the Jenkins UI with the right parameters | **Written, not run** |
| The agent's `known_hosts` holds the deployment host's key | **Not provisioned** — this is a host-side prerequisite, not a repository artefact |
| A `dockerhub-credentials` entry and a `deploy-ssh-key` entry exist in Jenkins | **Not created** |
| `./mvnw clean verify` is green | **No.** CLAUDE.md §13's precondition for this work is still unmet |

**Nothing in this document should be treated as proven until a `vX.Y.Z` tag has been built end to
end and the run recorded under [`docs/evidence/`](../evidence/).**

---

## 9. Known gaps

What this process does not do, stated rather than left to be discovered:

- **No deploy of anything but the application.** `compose.prod.yaml` starts the database
  alongside the application and `--remove-orphans` would replace it, but the database's own
  lifecycle — backup, restore, a migration rehearsal against production data — is not addressed
  here at all. A host running this stack for real needs a data-management story that this
  repository does not have.
- **No staging gate between staging and production.** `DEPLOY_ENV=staging` and
  `DEPLOY_ENV=production` are the same code path against different hosts. There is no promotion
  step that requires a staging soak before production, and no approval gate in the `Deploy` stage.
- **No TLS termination.** `SESSION_COOKIE_SECURE` is `false` unless a host sets it, which is
  correct for plain HTTP and wrong for anything reachable from the internet. A deployment that
  sets it must terminate TLS in front of the application; nothing here does.
- **No Ansible.** [`toolchain.md`](toolchain.md) §5 lists Ansible as the provisioning tool, and
  CLAUDE.md §13 lists Ansible provisioning as remaining Milestone 5 work. What exists is `scp`
  and `ssh` from the agent, which provisions nothing — it places two files and runs one script.
  Host provisioning, package installation, and the firewall are a human's job here.
- **No image digest recorded.** The deployment names a tag, not a digest. A tag can be re-pointed
  upstream; `docker push` prints the manifest digest and it is left in the console for this
  reason, but nothing pins a deployment to it. Recording it is the obvious next step and is not
  taken.
- **No notification.** A failed deployment is a red build and an archived report. Nobody is
  paged, and the rollback's outcome is not sent anywhere a person would see it without opening
  the build.
- **The FR-24 audit trail remains outstanding** (CLAUDE.md §11, §13). It is unrelated to this
  process — an audit trail records *security* events, which is a different concern from a
  deployment record.
