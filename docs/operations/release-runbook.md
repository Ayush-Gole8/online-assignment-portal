# Release runbook

## 1. What this document covers

The **procedure** for cutting a release and putting it on a host: what must be true before it
starts, what the mechanism does in what order, what record it leaves, and how to tell one kind of
failure from another when it does not work.

Three documents sit around this one, and the boundary between them is deliberate:

| Document | Answers |
| --- | --- |
| [`docker.md`](docker.md) | What the image is, and how the stack is composed on one machine |
| [`docker-cd.md`](docker-cd.md) | How a tag becomes a published image and reaches a host — the ref rules, the credentials, the host contract |
| **this document** | How to **run** a release: the checklist, the procedure, the record, and what a failure means |
| [`rollback.md`](rollback.md) | How to go back, and the one rule that decides whether going back is safe |
| [`backup-restore.md`](backup-restore.md) | What is backed up, when, and how to restore it |

> **Status: written, not run.** The mechanism described here has never been executed against a real
> host. `deploy/tests/release-rollback-demo.sh` was written to execute it end to end against a
> stubbed Docker daemon and a stub application, but **it has not run either** — so what the demo
> would and would not prove is stated in §9, and nothing here has behaved. No image has been built
> by this pipeline, no image has been pushed, no host has been contacted, and no rollback has fired
> on any deployment, real or simulated.

---

## 2. The release model

```
        release v1.0.0
             |
        health check            /actuator/health answers UP, from the agent
             |
        smoke test              the release identifies itself, renders, and guards
             |
   ===== v1.0.0 is STABLE =====  it is now the version a rollback returns to
             |
        release v1.0.1
             |
        health check            passes — a broken release can be a healthy one
             |
        smoke test FAILS        the business route answers an anonymous caller
             |
        recorded `failed`       the ledger is corrected before anything is undone
             |
        rollback v1.0.0         the last STABLE version, read from the ledger
             |
        health check            from the agent, over the network
             |
        smoke test              passes
             |
   ===== v1.0.0 is STABLE =====
```

The shape is the point. **A health check is not a release check.** A release that starts, binds its
port, answers `/actuator/health` with `UP` and refuses every submission is a release that passes a
health check and is unusable — and the step that catches it is the business-level smoke test, not
the health endpoint. That is why both exist, and why the model above shows the failure landing after
a *successful* health check rather than instead of one.

The second thing the shape shows: **stability is earned, not asserted.** A version becomes a
rollback target only after it has been verified from a different machine than the one running it.
The host's own opinion of a release — "my container started and my healthcheck passed" — is recorded
as `deployed`, which is a weaker fact deliberately kept separate from `success`. See §5.

---

## 3. Before the release

Run through this list before starting. Each line exists because of a specific failure it prevents;
the right-hand column names the script or document that would catch it if you skipped the line.

| # | Check | Why | Caught by |
| --- | --- | --- | --- |
| 1 | `./mvnw clean verify` is green | A release that has not passed unit and integration tests is not a release (CLAUDE.md §1, §9) | The pipeline; the `Release Version` stage is gated on the build |
| 2 | The change is merged to `main` through a pull request | `main` is protected; there are no direct pushes (CLAUDE.md §9) | The git host |
| 3 | The **schema change, if any, is backward compatible** | This is the rule in §7 and the one item on this list no script can check for you | You. It is a design decision, made before the release, not during it |
| 4 | The tag is `vX.Y.Z` on exactly the commit you mean | The tag **is** the version — see [`docker-cd.md`](docker-cd.md) §2 | `scripts/ops/release-version.cmd` |
| 5 | `DEPLOY_ENV` names the environment you mean, or `none` | `none` publishes without deploying, which is the normal way to cut a version and deploy it later | The `Deploy` stage's `when` |
| 6 | `DEPLOY_HOST` is the host you mean | It has no default on purpose. A deployment aimed at the wrong host by an unset variable is the failure the missing default exists to prevent | `scripts/ops/deploy-release.cmd` |
| 7 | The host's `.env` holds `DB_*` and `IMAGE_REPOSITORY` | These are the host's, not the pipeline's. The pipeline never reads them and will not create them | `deploy/deploy-on-host.sh` fails at the pull |
| 8 | The required volumes exist on the host | A named volume that has vanished is lost data, not missing configuration | `deploy/pre-release.sh` refuses, when the host has released before |
| 9 | The agent's `known_hosts` holds the host's key | Host key verification is **not** disabled, and must not be | The first `ssh`, with `Host key verification failed` |
| 10 | You know what is currently running | `scripts/ops/rollback-release.cmd` with `ROLLBACK_PLAN=1` prints it, and changes nothing | That command |

Item 3 is the one worth pausing on. Everything else on this list is a fact something can check.
Whether a migration is backward compatible is a decision about the shape of the change, and it has
to be made before the release is cut — see §7.

### The pre-release gate runs itself, on the host

Items 8, and the backup, are not things you have to remember. `deploy/deploy-on-host.sh` calls
`deploy/pre-release.sh` as its **first** step, before it rewrites a line of configuration or stops a
container. The gate:

1. captures the version the host is configured for right now;
2. reads the last known stable version from the ledger — *before* the swap makes `.env` disagree
   with it;
3. verifies the required volumes exist, and refuses if a host that has released before has lost
   one;
4. takes a MySQL backup, and refuses if the database is running and the dump fails.

It writes `releases/<version>.env` on the way through — the release's metadata — so that a release
which never starts still leaves a record of what was attempted. See §5.

A release that cannot be backed up does not get far enough to replace a container that is serving.
That ordering is the gate's entire purpose.

---

## 4. The procedure

Eleven steps. Steps 1–4 happen on the agent before anything is deployed; 5–11 run only when the
build is green **and** the revision is a tag **and** `DEPLOY_ENV` is not `none`.

| # | Step | Where | Script |
| --- | --- | --- | --- |
| 1 | Identify the release version | Agent | `scripts/ops/release-version.cmd` |
| 2 | Authenticate to the registry | Agent | `scripts/ops/docker-login.cmd` |
| 3 | Build the image, version baked in | Agent | `Dockerfile` |
| 4 | Tag and publish the versioned image | Agent | `scripts/ops/publish-image.cmd` |
| 5 | Place the composition and the host scripts | Agent → host | `scripts/ops/copy-host-scripts.cmd` |
| 6 | **Pre-release gate**: volumes, backup, metadata | Host | `deploy/pre-release.sh` |
| 7 | Record the version and environment in `.env` | Host | `deploy/deploy-on-host.sh` |
| 8 | Pull the exact image version | Host | `deploy/deploy-on-host.sh` |
| 9 | Replace the application container, wait for its healthcheck | Host | `deploy/deploy-on-host.sh` |
| 10 | Wait for health, from the agent, over the network | Agent | `scripts/ci/wait-for-health.cmd` |
| 11 | Smoke test, and record the outcome | Agent | `scripts/ops/smoke-test.cmd`, then the ledger |

[`docker-cd.md`](docker-cd.md) §3 describes steps 1–4 and the ordering reasons behind them. What
this runbook adds is what the host does and what it leaves behind.

### Why the pull comes before the container is replaced

Step 8 runs while the previous container is still serving. It is the step that proves the image
exists: if the tag was never pushed, the deployment fails **there**, with the old version still up,
rather than after the old container has been stopped and the new one turns out to be unavailable.

`--no-build` on step 9 is the same idea from the other side. The deployment uses the image that was
pushed and fails rather than quietly building a replacement from whatever is in the working
directory.

### Why the health check runs twice, and why that is not a duplicate

Step 9's `docker compose up --wait` blocks until the container's **own** healthcheck passes. That
proves the process started and its probe passed from inside the container, on the loopback
interface, against a database the container itself can see.

Step 10 polls `/actuator/health` from the **agent** — a different machine, over the network, through
whatever routes and firewall rules sit between them. That proves the deployment answers requests
arriving from outside it.

They are not the same claim, and the difference is not academic: a container can be perfectly well
and unreachable. Step 10 is the one a user's experience depends on.

The gate is `scripts/ci/wait-for-health.cmd` — the **same** script the pipeline uses for its own test
environment — and it requires the body to be a Spring health document reporting `UP`, not merely a
200 from the port. That rule is not decoration: the first end-to-end run of this project had Jenkins
listening on the test port, and a status-code-only gate passed against Jenkins. One definition of
"the application is serving", used everywhere, is what prevents that class of false green.

---

## 5. Release metadata: how the previous version is identified

Two artefacts, both on the host, under `DEPLOY_PATH` (default `/opt/assignment-portal`).

### `releases/ledger.tsv` — the append-only record

Tab-separated, one row per attempt:

```
<utc-timestamp> <version> <environment> <outcome> <note>
```

`outcome` is one of exactly three words, and the distinction between the first two is the whole
reason the file exists:

| Outcome | Written by | Means |
| --- | --- | --- |
| `deployed` | `deploy/deploy-on-host.sh`, on the host | The container started and its own healthcheck passed. **Nothing outside the host has confirmed it** |
| `success` | `scripts/ops/deploy-release.cmd`, on the agent | `/actuator/health` answered `UP` over the network **and** the smoke test passed |
| `failed` | Either, on the failure paths | The attempt did not come up, or did not verify |

**Only a `success` row makes a version STABLE, and only a stable version is a rollback target.**

#### Why `.env` cannot answer the question

`APP_IMAGE_TAG` in the host's `.env` is what the host is *configured* for. A deployment rewrites it,
and pulls, and starts a container — **before it can discover that the new version does not come up**.
So the moment a release fails, `.env` names the version that just failed.

A rollback that trusted `.env` would therefore redeploy the thing that just broke. That is not a
hypothetical: it is the exact sequence `deploy/deploy-on-host.sh` performs, and it is why
`deploy/release-ledger.sh` exists as a second opinion and why `deploy/rollback-on-host.sh` reads the
ledger rather than the configuration.

`release-ledger.sh` answers four questions, all of them about history rather than configuration:

```
sh release-ledger.sh last-stable       # most recent version with a success row
sh release-ledger.sh previous-stable   # the success before that one
sh release-ledger.sh current           # whatever the last row says, success or not
sh release-ledger.sh history           # the whole file, raw
```

Nothing is ever edited or deleted. A correction is a new row — the same rule, for the same reason,
as `submission_status_history`.

### `releases/<version>.env` — the release's context

Written by the pre-release gate before the swap, appended to by the deployment once the container is
up. It is the record a person reads when asking "what happened in this release":

| Key | When | What it is |
| --- | --- | --- |
| `RELEASE_VERSION`, `RELEASE_ENVIRONMENT`, `RELEASE_STARTED_AT` | Before the swap | What was attempted, and when |
| `IMAGE_REPOSITORY`, `IMAGE_TAG` | Before the swap | What was pulled |
| `PREVIOUS_CONFIGURED_TAG` | Before the swap | What `.env` said, before it was rewritten |
| `PREVIOUS_STABLE_VERSION` | Before the swap | What the ledger said the last good version was |
| `BACKUP_FILE` | Before the swap | The dump this release took. `none` when the database was not running and this was a first release |
| `RELEASE_OUTCOME`, `RELEASE_CONTAINER_STARTED_AT` | After the container starts | `deployed` |
| `SCHEMA_VERSION` | After the container starts | The Flyway version the database is at. **Recorded, never acted on** |
| `IMAGE_DIGEST` | After the container starts | The digest the registry knows for this tag |

`SCHEMA_VERSION` and `IMAGE_DIGEST` are recorded so that a person deciding whether a rollback is
safe has the facts in front of them. Nothing in this repository reads them back and acts on them —
see §7.

---

## 6. Health verification

Two levels, and a release has to pass both.

### `/actuator/health`

The endpoint the container's own healthcheck uses, and the one the agent polls over the network
(`scripts/ci/wait-for-health.cmd`). It reports the aggregate status of the application and its
dependencies; because the `db` health indicator is part of the aggregate, `UP` implies **the
database answered a query**. It is not a check that the application is correct — only that it is
running and its dependencies are reachable.

`management.endpoint.health.show-details: never` keeps the components out of the response. The
status is public; the topology is not.

### The business-level smoke test

`scripts/ops/smoke-test.ps1`, driven by `scripts/ops/smoke-test.cmd`. Seven checks:

| # | Check | Claim it establishes |
| --- | --- | --- |
| 1 | `/actuator/health` reports `UP` | The application is running and its dependencies answer |
| 2 | `/actuator/info` `app.version` equals the released version | **The release that was deployed is the release that is answering** |
| 3 | `/actuator/info` `app.environment` equals the target environment | The release was aimed at the host it landed on |
| 4 | `/login` answers 200 and renders an `email` field | A template rendered — not merely that a port is open |
| 5 | `/` answers 3xx for an anonymous visitor | The security filter chain is live |
| 6 | `/login`'s form carries a CSRF field | State-changing protection is in effect (`CLAUDE.md` §7 rule 3) |
| 7 | `/submissions` answers 3xx for an anonymous visitor | The authorization boundary holds on a **business route**, not only on `/` (`CLAUDE.md` §7 rule 8) |

Checks 2 and 3 are the important ones. A deployment that pulled a stale image passes 1, 4, 5, 6 and
7 perfectly — it is a healthy application, just not the one that was released. The version is baked
into the image at build time from the same value as the image's tag (`Dockerfile`), so it cannot be
satisfied by the host's configuration: it is the artefact identifying itself.

Checks 6 and 7 are about the **application**, not about a port, and neither needs an account. That
matters: a smoke test that stored a credential would have to keep it on the machine it is checking,
which is the one thing a smoke test should never carry.

The smoke test deliberately does **not** check that the database has the right rows, that an upload
works, or that a reviewer can approve. Those are the browser suite's job on a deployment the
pipeline built itself, and a smoke test that duplicated them would be a slower, weaker copy — the
pyramid stays a pyramid (`CLAUDE.md` §6).

### What passing both entitles you to say

"The release that was deployed is the release that is answering, it is aimed at the environment it
landed in, its templates render, its security filter chain is live, and its business routes are
guarded." Nothing more — and in particular nothing about the correctness of any particular
submission, upload or review.

---

## 7. The backward-compatibility rule

This is the constraint that decides whether a rollback is available to you, and it is a **release
planning** decision, not a script. No script in this repository can enforce it.

> **Application rollback is safe only when the database changes the release carried are backward
> compatible.**

**Flyway migrations are forward-oriented.** `V1`, `V2`, `V3` are applied in order and there is no
`undo`. `deploy/compose.prod.yaml` runs with `validate-on-migrate: true` and
`baseline-on-migrate: false`, so a schema that does not match the migrations the running application
carries is an application that **will not start** — not one that starts and misbehaves.

Put the two together and the consequence is exact:

- A rollback restores the previous **application** against the **current** schema. It does **not**
  reverse a migration. `deploy/rollback-on-host.sh` says so in its own header, and
  [`rollback.md`](rollback.md) §2 states the rule in full.
- Therefore an older application is only runnable against a newer schema if that schema is backward
  compatible with it.

What "backward compatible" means in practice, by change:

| Change | Backward compatible? | Why |
| --- | --- | --- |
| Add a table | Yes | The old application does not reference it |
| Add a **nullable** column, or one with a default | Yes | The old application's `INSERT`s still satisfy it |
| Add an index | Yes | Invisible to the application |
| Widen a column | Yes | The old application reads a value it can still parse |
| Add a `NOT NULL` column **without** a default | **No** | The old application's `INSERT`s omit it and are rejected |
| Rename a column or table | **No** | The old application's SQL names the old identifier |
| Drop a column or table | **No** | The old application's SQL names something that is gone |
| Narrow a type, or add a `CHECK` the old rows violate | **No** | The old application writes values the new constraint refuses |

So: **when a release needs to be rollback-able, the schema change must be made in the compatible
column of that table.** Add the new column nullable in this release, backfill and tighten it in a
later one; rename by adding the new name, dual-writing, and dropping the old name releases later.
This is the expand/contract discipline, and adopting it is the price of having a rollback path at
all.

A release whose migration is **not** backward compatible cannot be rolled back by redeploying old
code. Its recovery path is a forward fix — publish a new version — which is the subject of
[`rollback.md`](rollback.md) §8.

The migrations this repository has shipped so far are all forward-compatible in this sense: `V1`
creates `users`, `V2` creates `submissions`, `V3` creates `submission_status_history`. V3 added a
table, which is the "Yes" row above — which is why a rollback across it would be safe, if one ever
ran. **No migration has been exercised by a rollback**, because no rollback has fired.

---

## 8. Classifying a failure

A release that has gone wrong produces a red build and a report. The report says *what* happened;
this table is for working out *which thing* broke, because the five kinds have different first
moves and three of them are not fixed by rolling back.

**Read the failure in this order**: the stage that failed, then the report, then the host. The stage
tells you the layer; the layer tells you the class.

| Class | Failure signature | Where it shows | First move |
| --- | --- | --- | --- |
| **Deployment failure** | `FAILED, HOST UNREACHABLE`; `Host key verification failed`; `unknown flag: --wait`; the pull failed with `manifest unknown` | Step 5, or step 8's pull | **Not a code problem.** Fix the route, the `known_hosts` entry, the Compose version, or the published tag. Nothing was changed on the host in the unreachable case |
| **Application failure** | The container exits or never becomes healthy; `compose up --wait` times out; the smoke test's check 2 reports the wrong version | Step 9, or the container's own log | Read the container's log on the host (`docker compose -f compose.prod.yaml logs app`). A version mismatch on check 2 with a healthy container is a **stale or wrong image** — a deployment failure wearing an application failure's clothes |
| **Database failure** | `/actuator/health` reports `DOWN` and the `db` indicator is the reason; Flyway fails validation at startup; the pre-release gate cannot take a backup | Step 6, or startup | The application will not start against a schema that does not match its migrations. **Check `SCHEMA_VERSION` in `releases/<version>.env` against the migration set in the release you are deploying** before doing anything else — a mismatch here means a rollback will not help (see §7) |
| **Storage failure** | Uploads 404 for files that exist as rows; the uploads archive is empty; `pre-release.sh` refuses because a required volume is missing | Step 6, or at runtime | A missing named volume is **lost data**, not missing configuration — which is why the gate refuses rather than recreating it. Restore from a backup ([`backup-restore.md`](backup-restore.md)) and investigate the daemon and the disk |
| **Test failure** | The build is red in `Unit Tests`, `Integration Tests` or `Selenium E2E`; no tag is built and nothing is published | Steps 1–4 never run | **This is the system working.** Nothing reached a host. Fix the test or fix the code; a red pipeline is never bypassed (`CLAUDE.md` §9) |

Two distinctions inside that table are worth stating separately, because they are the ones most
easily got wrong:

- **An unreachable host is not a failed release.** Nothing was changed, and the report says so
  rather than claiming to have rolled back. There is nothing to undo.
- **A failed integration suite is not a deployment incident.** It is a red build before any host was
  touched. Treating it as a deployment incident is how a pipeline gets bypassed.

### What each class does *not* call for

| Class | Do not |
| --- | --- |
| Deployment failure | Re-run the pipeline hoping for a different result. The route, the key or the tag is wrong; a retry reaches the same wall |
| Application failure | Roll back before reading the container log. The log usually names the cause, and a rollback destroys the evidence |
| Database failure | Roll back. If the schema is not backward compatible, the rollback will not start either (§7) |
| Storage failure | Deploy forward. The problem is data, not code |
| Test failure | Bypass it. `CLAUDE.md` §9 is explicit |

---

## 9. Verification status

**None of the release mechanism has been executed — not on a host, and not even against a stub.**
`deploy/tests/release-rollback-demo.sh` was written to make the mechanism runnable, and has never
been run: the environment this work was done in refused every attempt to execute it. The distinction
between *written* and *run* is the whole of this section, and every row below is on the wrong side of
it.

| Claim | Status |
| --- | --- |
| `deploy/pre-release.sh` refuses when a required volume has vanished and the host has released before | **Written, not run.** The refusal path is code; the demo would not exercise it either, since its stub reports every volume present |
| `deploy/pre-release.sh` takes a MySQL dump before the swap, and refuses when it cannot | **Written, not run.** The demo would run the real `backup.sh` against a stubbed `mysqldump`, exercising the ordering, the filename, the emptiness check and the `MySQL dump` marker check — but the demo itself has not run, and the dump would be a stub in any case |
| The ledger records `deployed` / `success` / `failed` and `last-stable` returns the right version | **Written, not run** |
| A release that fails to verify is corrected to `failed` before the rollback runs | **Written, not run.** The demo would perform the same two ledger writes the agent performs, in the same order |
| `deploy/rollback-on-host.sh` chooses the last stable version when `.env` names the failed one | **Written, not run** |
| The rollback restores the exact previous image and the container comes up on it | **Written, not run** |
| `scripts/ci/wait-for-health.cmd` accepts a healthy deployment and rejects anything else | **Written, not run.** The "another service on the port" case it was written for is recorded in `docs/evidence/` |
| `scripts/ops/smoke-test.ps1` passes a correct deployment and fails on check 7 | **Written, not run.** The demo's intended simulated failure is precisely check 7: a version that answers `/actuator/health` with `UP` and serves `/submissions` unguarded |
| `docker compose up --wait` blocks on the healthcheck | **Not run.** No real Docker daemon has been involved |
| A real image is pulled from a real registry by tag | **Not run** |
| A real MySQL dump is taken and restores | **Not run** |
| The whole thing works over `ssh` to a provisioned host | **Not run** |
| `./mvnw clean verify` is green | **No.** `CLAUDE.md` §13's precondition for the delivery work is still unmet |

There is no transcript to point at. `docs/evidence/release-rollback-demo.md` does not exist, because
the run it would record has not happened. Everything in this runbook is therefore a **design**, not a
result: it is a description of what the scripts were written to do, reviewable as code, unproven as
behaviour.

**What the mechanisms are intended to establish** — and what running the demonstration would be
evidence for — is the release and rollback **ordering**: the ledger semantics, the target selection,
the health gate and the smoke test. Reading the scripts establishes nothing about whether they
execute.

**What nothing here establishes**: that an image builds, that a registry accepts a push, that a host
can be reached, that MySQL dumps and restores, or that anything in this document works anywhere.

---

## 10. Known gaps

- **No notification.** A failed release is a red build and an archived report. Nobody is paged, and
  a rollback's outcome is not sent anywhere a person would see it without opening the build.
- **No staging gate.** `DEPLOY_ENV=staging` and `DEPLOY_ENV=production` are the same code path
  against different hosts. There is no soak period and no approval gate.
- **No TLS.** `SESSION_COOKIE_SECURE` is `false` unless a host sets it. A deployment reachable from
  the internet needs a terminator in front of it; nothing here provides one.
- **No retention policy for the ledger or the backups.** Neither file is pruned. `ledger.tsv` grows
  by one line per attempt, which is fine indefinitely; `backups/` grows by one dump per release,
  which is not, and no script rotates it ([`backup-restore.md`](backup-restore.md) §4).
- **No backup is verified by restoring it.** The gate proves a dump is non-empty and looks like a
  dump. It does not prove it restores.
- **The `DEPLOY_PATH` contract is shared with the Ansible role but not enforced by it.** Both
  assume `/opt/assignment-portal` and both copy the same `compose.prod.yaml`. Neither checks the
  other ([`ansible.md`](ansible.md) §4).
- **The FR-24 audit trail is still outstanding** (`CLAUDE.md` §11, §13). It is a *security* event
  log, a different concern from this deployment record.
