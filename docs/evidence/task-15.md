# Task 15 -- Versioned release, deployment, and rollback

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 15: Versioned release, deployment, and rollback |
| Objective | Publish and deploy a deliberate version, verify the release externally, record a stable version, and recover when a deployment fails. |
| Implementation | Only a vX.Y.Z tag publishes. Jenkins uses dockerhub-credentials and deploy-ssh-key from the credentials store. Host scripts (deploy-on-host.sh, pre-release.sh, release-ledger.sh) rewrite .env, pull the exact tagged image, start Compose, wait for health, run smoke tests (health UP, version match, /submissions redirects to /login), and append an outcome to releases/ledger.tsv. Rollback reads the ledger for the last stable version, restores it, verifies, and records the outcome. Flyway migrations are forward-only; rollback requires backward-compatible schema changes. |
| Primary files | scripts/ops/release-version.cmd, scripts/ops/publish-image.cmd, scripts/ops/deploy-release.cmd, scripts/ops/rollback-release.cmd, deploy/deploy-on-host.sh, deploy/rollback-on-host.sh, deploy/release-ledger.sh, docs/operations/release-runbook.md, docs/operations/rollback.md |
| Command/demo | With a provisioned staging host and all Jenkins credentials configured, push a vX.Y.Z tag and select staging. For a controlled rollback drill, use the documented release/rollback procedure and a previously successful version. |
| Expected result | The exact tagged image is published and reported at /actuator/info; external health and smoke checks pass before a success ledger entry is written; rollback restores a different prior stable version, verifies it, and writes a rollback ledger entry. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture tag/build identity, Docker Hub registry manifest, sanitised deployment/health/smoke logs, ledger outcomes before and after rollback, and /actuator/health and /actuator/info after rollback. No release or rollback evidence has been captured.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
