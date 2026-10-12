# Task 15 — Versioned release, deployment, and rollback

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 15: Versioned release, deployment, and rollback |
| Objective | Publish and deploy a deliberate version, verify the release externally, record a stable version, and recover when a deployment fails. |
| Implementation | Only a vX.Y.Z tag publishes. Jenkins uses credentials for registry/SSH, host scripts update Compose and append an outcome ledger, smoke checks verify health/version/security behavior, and rollback selects a prior stable image. Flyway migrations are forward-only; rollback requires backward-compatible schema changes. |
| Primary files | [scripts/ops/release-version.cmd](../../scripts/ops/release-version.cmd), [scripts/ops/publish-image.cmd](../../scripts/ops/publish-image.cmd), [scripts/ops/deploy-release.cmd](../../scripts/ops/deploy-release.cmd), [scripts/ops/rollback-release.cmd](../../scripts/ops/rollback-release.cmd), [deploy/deploy-on-host.sh](../../deploy/deploy-on-host.sh), [deploy/rollback-on-host.sh](../../deploy/rollback-on-host.sh), [deploy/release-ledger.sh](../../deploy/release-ledger.sh), [docs/operations/release-runbook.md](../../docs/operations/release-runbook.md), [docs/operations/rollback.md](../../docs/operations/rollback.md) |
| Command/demo | With a provisioned staging host and all Jenkins credentials configured, push a vX.Y.Z tag and select staging. For a controlled rollback drill, use the documented release/rollback procedure and a previously successful version. |
| Expected result | The exact tagged image is published and reported at /actuator/info; external health and smoke checks pass before a success ledger entry; rollback restores a different prior stable version and verifies it. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture tag/build identity, registry manifest, sanitized deployment/health/smoke logs, ledger outcomes, rollback report, and health/version after rollback. No release or rollback evidence is currently captured.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

