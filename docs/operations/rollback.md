# Rollback

> **Status: written, not run.** No rollback has fired — not on a host, and not against a stub.
> `deploy/tests/release-rollback-demo.sh` was written to exercise the host-side mechanism under a
> stubbed Docker daemon and has never been executed. Every claim in this document is a description
> of what the scripts were written to do; §9 states that boundary claim by claim.

## 1. What a rollback is, and what it is not

**What it is.** Pointing a deployment back at a version that is already known to have worked, and
verifying that it is serving. That is all.

**What it is not.** It is not an undo. Three things a rollback does not touch, and each has a reason:

| Not undone | Why | What to do instead |
| --- | --- | --- |
| The published image | The registry is a record. A release that was published stays published — deleting a tag somebody may already have deployed is a worse failure than the one being recovered from | Leave it. Publish a new version when the fix is ready |
| A database migration | Flyway is forward-only (ADR-003). There is no `undo`, and this repository does not attempt to synthesise one | See §2 — this is the constraint the whole page is about |
| Whatever the failed release already served | Traffic was live during the window | A rollback **bounds** the window; it does not erase it. Say so in the incident note |

A rollback is not a separate operation either. It is the second half of a failed deployment:
`scripts/ops/deploy-release.cmd` calls the same command on its failure path that an operator calls by
hand (`scripts/ops/rollback-release.cmd`). That is a design property — the delegation is a literal
`call` in the script — and it is what makes the path an operator takes at three in the morning the
same one a failing build takes. Neither path has been executed (§9).

---

## 2. The rule

> **Application rollback is safe only when the database changes the release carried are backward
> compatible.**

This is the constraint everything else on this page follows from, and it is a **release planning**
decision. No script in this repository enforces it, because no script can: it is a judgement about
the shape of a schema change, made when the change is designed.

**Flyway migrations are forward-oriented.** Applied in order, never reversed. `compose.prod.yaml`
runs with `validate-on-migrate: true` and `baseline-on-migrate: false`, so an application whose
migrations do not match the database **will not start** — it is not a release that starts and
misbehaves, it is a release that fails to boot.

A rollback therefore restores the previous **application** against the **current** schema. The older
application runs only if that schema is backward compatible with it.

### What backward compatible means, by change

| Change | Rollback-safe? | Why |
| --- | --- | --- |
| Add a table | Yes | The old application does not reference it |
| Add a nullable column, or one with a default | Yes | The old application's `INSERT`s still satisfy it |
| Add an index | Yes | Invisible to the application |
| Widen a column | Yes | The old application reads a value it can still parse |
| Add a `NOT NULL` column with no default | **No** | The old application's `INSERT`s omit it and are rejected |
| Rename a column or a table | **No** | The old application's SQL names the old identifier |
| Drop a column or a table | **No** | The old application's SQL names something that is gone |
| Narrow a type, or add a `CHECK` old rows violate | **No** | The old application writes values the new constraint refuses |

### The consequence for how changes are made

**When a release needs to be rollback-able, the schema change must land in the "Yes" rows.** The
discipline that achieves this is expand/contract:

1. **Expand** — add the new column nullable, or the new table, in this release. Old code ignores it.
2. **Migrate** — backfill, and move writers over, in a later release.
3. **Contract** — drop the old column or table, once no deployed version references it.

Renames become add-new + dual-write + drop-old. A tightening of a constraint becomes add-nullable,
backfill, then tighten in a later release. It costs releases, and it is the price of having a
rollback path at all.

A release whose migration is **not** backward compatible cannot be recovered by redeploying old
code. Its recovery path is a forward fix — §8.

**What this repository has shipped so far is rollback-safe.** `V1` creates `users`, `V2` creates
`submissions`, `V3` creates `submission_status_history`. V3 added a table: the first "Yes" row.

---

## 3. When it fires

### Automatically

Inside a release. `scripts/ops/deploy-release.cmd` calls `rollback-release.cmd` when:

| Condition | Report outcome |
| --- | --- |
| The release started on the host but `/actuator/health` did not answer `UP` over the network | `FAILED, ROLLED BACK TO <version>` |
| The release started but the smoke test failed | `FAILED, ROLLED BACK TO <version>` |
| The release did not start at all, after the host had been reached | `FAILED, ROLLED BACK TO <version>` |

It does **not** fire when the host was never reached — nothing was changed, so there is nothing to
undo, and the report says `FAILED, HOST UNREACHABLE - NOTHING CHANGED` rather than claiming to have
rolled back. It does not fire on a first deployment (there is no previous version), and it refuses
to "roll back" a redeployment of the version already running.

### By hand

When the release verified and *then* went wrong — a defect that only appears under real traffic, a
dependency that failed later, a decision that this version should not be serving.

```cmd
rem Identify the target without changing anything, and see why it was chosen:
set DEPLOY_HOST=deploy@portal.example.com
set DEPLOY_ENV=production
set ROLLBACK_PLAN=1
scripts\ops\rollback-release.cmd

rem Do it:
set ROLLBACK_PLAN=
scripts\ops\rollback-release.cmd
```

---

## 4. Identifying the last known stable version

This is the step the whole mechanism is built around, because the obvious source of the answer is
wrong.

**`.env` cannot answer it.** `APP_IMAGE_TAG` is what the host is *configured* for. A deployment
rewrites it, then pulls, then starts a container — **before it can find out that the new version
does not come up**. So the moment a release fails, `.env` names the version that just failed. A
rollback that trusted `.env` would redeploy the thing that just broke.

**The ledger answers it.** `releases/ledger.tsv` is append-only and records the *outcome* of each
attempt. Only a `success` row makes a version stable — and a `success` row is written by the agent
after its own network health poll and smoke test have passed, not by the host when its container
came up. A version becomes a rollback target only after it has been verified **from outside the
machine running it**.

`deploy/rollback-on-host.sh` applies this in three cases:

| Situation | Target | Reason printed |
| --- | --- | --- |
| `.env` names a version that is **not** the ledger's last stable one | the ledger's **last stable** | The release changed `.env` and then failed. This is the automatic case |
| `.env` matches the ledger's last stable version | the last stable version that is a **different** version | The operator is deliberately stepping one release back |
| A version was named on the command line | **that** version | Explicit instruction; it wins |

### Why "the stable version before it" means "a *different* version"

"Last stable" is a query over history: the most recent row whose outcome is `success`. "The one
before it" is a query over the same history, and it would be wrong to read it as *the previous row*.

A `success` row is written by the agent after a deployment verifies — and also after a **rollback**
verifies. So recovering from one failed release leaves **two `success` rows for the same version**:
the original deployment, and the rollback that returned to it. That is the ordinary outcome of
using this mechanism at all, not an edge case.

If `previous-stable` simply took the second-to-last row, it would then name the version already
running. Two things follow, and both are wrong in the same direction — they make a non-event look
like a recovery:

- `rollback-on-host.sh`'s *"the host is running X, the only version that has ever succeeded, so
  there is no earlier stable version to step back to"* refusal keys on an **empty** answer. It
  would never fire.
- The request would instead land on the *"X is already the configured version; there is nothing to
  change"* branch, which exits **0** and prints `rolled-back-to=X`. The agent wrapper reads that as
  "the host restored X", verifies X (it passes — X is running), and writes a **`success` row** on
  the strength of a rollback that moved nothing.

`release-ledger.sh previous-stable` therefore returns the most recent `success` row whose version
**differs** from `last-stable`, or nothing. Filtering by inequality can only ever narrow the
answer: it yields a version that genuinely recorded a success and is not the one already running,
or an empty line — and an empty line produces the explicit refusal above rather than a wrong
target.

```cmd
rem See the decision without performing it — the host prints target, from, and why:
scripts\ops\rollback-release.cmd            rem with ROLLBACK_PLAN=1
```

The raw reading, if you are on the host:

```bash
cd /opt/assignment-portal
DEPLOY_DIR=/opt/assignment-portal sh release-ledger.sh last-stable
DEPLOY_DIR=/opt/assignment-portal sh release-ledger.sh history
```

---

## 5. The operational flow

`scripts/ops/rollback-release.cmd [<version>]`. Seven steps, in this order:

| # | Step | Where | What happens |
| --- | --- | --- | --- |
| 1 | Identify the target | Host | The ledger, or the version named on the command line — §4 |
| 2 | Say what it is about to do | Host | Prints the target and the reason it was chosen, before changing anything |
| 3 | Place the host tooling | Agent → host | `copy-host-scripts.cmd`. A host reached mid-incident may hold scripts from an older release; it is given the current ones first |
| 4 | Snapshot the database | Host | A MySQL dump, taken **while the failed version is still the one on disk**, so that if the rollback makes things worse there is still something to return to |
| 5 | Restore the exact image and restart | Host | Rewrites `APP_ENV`/`APP_IMAGE_TAG`, `compose pull app`, then `compose up -d --no-build --remove-orphans --wait app`. The pull happens **before** anything is replaced, so a missing image fails with the failed version still running |
| 6 | Wait for health, then smoke test | **Agent** | `/actuator/health` over the network, then the seven-check smoke test. Both use the pipeline's own scripts |
| 7 | Report and record | Agent | `target/rollback-report.txt`, and a `success` row in the ledger |

Steps 5 and 6 happen in different places on purpose. Step 5 proves the container **started** and its
own healthcheck passed; step 6 proves the restored version **answers requests from another
machine**. A rollback that only proved the first would report success for a version that is up and
not serving.

The expected version passed to the smoke test is the version that was **restored**, so check 2
proves the restored image is the one answering — not merely that something healthy is.

`ROLLBACK_VERSION` pins the target explicitly. `deploy-release.cmd` sets it to the version the host
reported before the failed release started, so the automatic path makes a decision the ledger has
already confirmed.

---

## 6. The three outcomes

`target/rollback-report.txt` distinguishes them, because "it rolled back" and "it rolled back and
that also failed" are different incidents.

| Report outcome | Exit | What it means | What to do |
| --- | --- | --- | --- |
| `SUCCESS` | 0 | The target version is running and passed health **and** the smoke test | Confirm with whoever reported the problem, then decide whether to forward-fix |
| `FAILED, NOTHING RESTORED` | 1 | The host did not restore a version. The host-side output names the step | Usually the image for the target cannot be pulled, or the ledger records no successful release to return to |
| `FAILED, RESTORED <v> DID NOT VERIFY` | 1 | The target was restored but does not answer health or the smoke test | **Needs a human.** The host is in an unknown state |
| `FAILED, HOST UNREACHABLE - NOTHING CHANGED` | 1 | The host was never reached | Nothing to undo. Fix the route or the key |

**The third row is the one that matters.** A restored version that does not verify means the problem
is not the release — it is the database, the storage, or the host. Before doing anything else, check
in this order:

1. `docker compose -f compose.prod.yaml logs app` on the host — the application's own account of why
   it will not start.
2. `SCHEMA_VERSION` in `releases/<target>.env` against the migration set in the tag you restored. A
   mismatch means the schema moved past what the older application can start against, and §2 is why
   that is possible.
3. The `db` container's state and the mysql volume. `/actuator/health` reporting `DOWN` with `db` as
   the reason is a database failure wearing a deployment failure's clothes.
4. The uploads volume, if the failure is uploads rather than startup.

If the cause is data, the next step is a restore — [`backup-restore.md`](backup-restore.md) — not
another rollback.

---

## 7. What a rollback does not do

| Not restored | Note |
| --- | --- |
| Database schema | §2. Flyway is forward-only |
| Database **content** | A rollback does **not** restore the pre-release dump. The dump is taken so that a restore is *possible*, not so that a rollback performs one — reversing a release's rows would discard everything written since, including work by users who had nothing to do with the release |
| Uploads | Same reasoning. `backup.sh uploads` archives the volume; nothing restores it automatically |
| The published image | §1 |
| Anything served during the failed window | §1 |
| `ledger.tsv` rows | The ledger is append-only. A rollback **adds** a row; it does not remove the `failed` one, and it should not — the failed attempt is the record of an incident |

If the release damaged data rather than merely failing to serve, a rollback is not the answer and
[`backup-restore.md`](backup-restore.md) is.

---

## 8. When a rollback will not work: the forward fix

A rollback is unavailable in two situations, and both have the same answer.

1. **The schema change was not backward compatible.** The older application cannot start against
   the migrated schema (§2).
2. **The problem is data, not code.** A rollback restores code; it does not restore rows or files.

In both cases the recovery path is **forward**: fix the defect, publish a new version, deploy it
through the normal process ([`release-runbook.md`](release-runbook.md) §4). Nothing about that is
improvised — it is the same pipeline, the same gates, and the same verification.

Two things make a forward fix harder than they need to be, and both are worth naming:

- **The ledger now records a `failed` row for the current version.** That is correct and is not a
  problem: `last-stable` still points at the version before it, so a rollback remains available
  right up until the forward fix is deployed.
- **`/actuator/info` is reporting a version that is known bad.** If the failed release is still
  running, a smoke test that expected it will keep failing. The fix is a new version, which is the
  point.

If a rollback is not available and a forward fix will take time, the question to ask is not "how do
I roll back" but "should this deployment be taken out of service, and who needs to know". That is an
operational decision outside this document's scope.

---

## 9. Verification status

| Claim | Status |
| --- | --- |
| `rollback-on-host.sh` chooses the ledger's last stable version when `.env` names the failed one | **Written, not run** |
| `previous-stable` returns a *different* version from `last-stable` when one version holds two `success` rows | **Written, not run.** Corrected in the engineering review of 2026-10-12: the previous definition took the second-to-last `success` row and so returned the version already running, defeating the "no earlier stable version" refusal below it. The corrected query is `stable_versions \| awk -v last="$(…last-stable)" '$0 != last' \| tail -n 1`. It narrows the answer or empties it; it can never name a version that did not record a success |
| `rollback-on-host.sh` refuses when there is no earlier stable version | **Written, not run.** The refusal is on the empty case, which the correction above is what makes reachable |
| `rollback-on-host.sh plan` prints the target and the reason and changes nothing | **Written, not run** |
| The rollback takes a pre-rollback snapshot | **Written, not run** |
| `release-ledger.sh record` writes a `success` row that makes a version a target | **Written, not run** |
| The rollback restores the exact image tag into `.env` | **Written, not run** |
| `scripts/ops/rollback-release.cmd` parses `rolled-back-to=`, waits for health from the agent, runs the smoke test, records `success` | **Written, not run.** It needs an `ssh` route to a real host |
| The automatic path in `deploy-release.cmd` calls the same command an operator does | **Read, not executed.** The delegation is a literal `call` in `deploy-release.cmd`; the branch has never been taken |
| A rollback fires on a real host, through a real registry | **Not run.** No host has been contacted, no image has been pushed, and no rollback has fired |

`deploy/tests/release-rollback-demo.sh` was written to exercise the host-side mechanism against a
stubbed Docker daemon, and **has never run** — the environment this work was done in refused every
attempt to execute it. There is therefore no transcript: `docs/evidence/release-rollback-demo.md`
does not exist. Nothing in this document has been executed, by any route.

Every row above is *written, not run*. The distinction the rows would otherwise draw — host-side
mechanism exercised, agent-side wrapper not — is not one this revision has evidence for. The
agent-side wrapper is additionally the part with the least evidence, since it does nothing the
host-side scripts do not and adds an `ssh` hop nothing has crossed.
