#!/bin/sh
# ===========================================================================
# Operational rollback — return the host to a version that is known to have
# worked.
#
# Runs ON the host. Driven either by scripts/ops/rollback-release.cmd (a person
# restoring service) or by the failure path of scripts/ops/deploy-release.cmd
# (the automatic second half of a failed release).
#
# What "known to have worked" means, and why it is not simply `.env`
#   A deployment rewrites `.env`'s APP_IMAGE_TAG and pulls the new image BEFORE it
#   can learn that the new image does not come up (deploy-on-host.sh). So when a
#   release fails, `.env` names the version that just failed. The version to
#   return to is therefore read from the release LEDGER — the append-only record
#   of which attempts recorded a success — and not from mutable configuration.
#   release-ledger.sh explains the file; this script is its main consumer.
#
# The flow, in the order the requirement states it
#   1. identify the last known stable version   (the ledger, or the argument)
#   2. capture the current state first          (a backup, before anything moves)
#   3. restore that exact container image       (app, by digest-resolved tag)
#   4. restart the application                  (recreate the container on it)
#   5. wait for health                          (compose `--wait`: the container's
#                                                own healthcheck, i.e. /actuator/health)
#   6. smoke verification and a report          (the AGENT does this step, over
#                                                the network — because "the
#                                                container says it is well" and "it
#                                                answers requests from another
#                                                machine" are different claims)
#   7. record the outcome                       (ledger, and the release metadata)
#
# Step 6 is deliberately not here. This script proves the container started and
# its own probe passed; rollback-release.cmd then polls the same endpoint from
# the agent and runs the smoke test, exactly as the deploy path does. Reusing the
# one readiness gate means "the application is serving" has a single definition.
#
# What a rollback does NOT do
#   It does not reverse a Flyway migration, and it must not be expected to.
#   Migrations are forward-only (ADR-003). Application rollback is safe ONLY when
#   the database changes a release carried are BACKWARD COMPATIBLE — new nullable
#   columns, new tables, widened types — so that the older application can run
#   against the newer schema. A release whose migration is not backward-compatible
#   cannot be rolled back by redeploying old code; it needs a forward fix. This
#   rule is not enforced here because no script can enforce it; it is a release
#   planning constraint, and release-runbook.md §6 and rollback.md §2 state it.
#
# Usage
#   sh rollback-on-host.sh plan   [<version>] [<environment>]
#       Identify the version that would be restored and say why. Changes nothing.
#
#   sh rollback-on-host.sh rollback [<version>] [<environment>]
#       Perform it. Prints `rolled-back-to=<version>` for the caller.
#
#   <version> given      -> roll back to exactly that version.
#   <version> omitted    -> the ledger decides:
#                             `.env` names a version that is not the last stable
#                             one -> that last stable version (the failed release)
#                             otherwise                 -> the stable version
#                                                          before it (a manual
#                                                          step back)
#
# Exit codes
#   0  the container is running the target version and its healthcheck passed
#   2  the arguments were refused, or there is nothing to roll back to
#   1  the rollback failed — a missing image, or a container that did not come up
# ===========================================================================

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=deploy/host-common.sh
. "$SCRIPT_DIR/host-common.sh"

MODE="${1:-}"
REQUESTED_VERSION="${2:-}"
REQUESTED_ENVIRONMENT="${3:-}"

[ -n "$MODE" ] || FAIL_CODE=2 fail "usage: rollback-on-host.sh <plan|rollback> [<version>] [<environment>]"

cd "$DEPLOY_DIR" || fail "cannot enter $DEPLOY_DIR"
command -v docker >/dev/null 2>&1 || fail "docker is not on PATH on this host."
[ -f "$COMPOSE_FILE" ] || fail "$DEPLOY_DIR/$COMPOSE_FILE does not exist; this host has never been deployed to."

CONFIGURED_TAG="$(read_env_var "$MANAGED_TAG_VAR" || true)"
CONFIGURED_ENVIRONMENT="$(read_env_var "$MANAGED_ENV_VAR" || true)"
LAST_STABLE="$(sh "$SCRIPT_DIR/release-ledger.sh" last-stable || true)"
PREVIOUS_STABLE="$(sh "$SCRIPT_DIR/release-ledger.sh" previous-stable || true)"

# ---------------------------------------------------------------------------
# 1. Identify the last known stable version.
# ---------------------------------------------------------------------------
choose_target() {
    if [ -n "$REQUESTED_VERSION" ]; then
        require_version "$REQUESTED_VERSION" "version to roll back to"
        TARGET="$REQUESTED_VERSION"
        REASON="named explicitly on the command line"
        return 0
    fi

    if [ -z "$LAST_STABLE" ]; then
        FAIL_CODE=2 fail "the ledger records no successful release, so there is no version known to have worked. There is nothing to roll back to; a host in this state needs a forward deployment, not a rollback."
    fi

    if [ "$CONFIGURED_TAG" != "$LAST_STABLE" ]; then
        # `.env` disagrees with the ledger: the last release changed `.env` and
        # then failed. This is the automatic case, and the answer is the last
        # version that actually recorded a success.
        TARGET="$LAST_STABLE"
        REASON=".env is configured for '${CONFIGURED_TAG:-<none>}', which is not the last version to record a success; the ledger's last stable is $LAST_STABLE"
        return 0
    fi

    if [ -z "$PREVIOUS_STABLE" ]; then
        FAIL_CODE=2 fail "the host is running $LAST_STABLE, the only version that has ever succeeded, so there is no earlier stable version to step back to. Name a version explicitly to roll back to one that failed."
    fi

    # `.env` matches the last stable version: the operator is asking to step one
    # release back, which is a deliberate act rather than a recovery.
    TARGET="$PREVIOUS_STABLE"
    REASON=".env is already configured for the last stable version ($LAST_STABLE); stepping back to the stable version before it"
}

choose_target

[ -n "$REQUESTED_ENVIRONMENT" ] && TARGET_ENVIRONMENT="$REQUESTED_ENVIRONMENT" || TARGET_ENVIRONMENT="$CONFIGURED_ENVIRONMENT"
[ -n "$TARGET_ENVIRONMENT" ] || FAIL_CODE=2 fail "no environment is configured in $ENV_FILE and none was given. Pass it: rollback-on-host.sh $MODE $TARGET <environment>."
require_environment "$TARGET_ENVIRONMENT"

say "[rollback] Configured version : ${CONFIGURED_TAG:-<none>}"
say "[rollback] Last known stable  : ${LAST_STABLE:-<none>}"
say "[rollback] Target version     : $TARGET"
say "[rollback] Why               : $REASON"

# ---------------------------------------------------------------------------
# plan — stop here, having changed nothing.
# ---------------------------------------------------------------------------
if [ "$MODE" = "plan" ]; then
    echo "target=$TARGET"
    echo "from=${CONFIGURED_TAG:-none}"
    echo "reason=$REASON"
    exit 0
fi

[ "$MODE" = "rollback" ] || FAIL_CODE=2 fail "'$MODE' is not a mode. Expected 'plan' or 'rollback'."

if [ "$TARGET" = "$CONFIGURED_TAG" ]; then
    say "[rollback] $TARGET is already the configured version; there is nothing to change."
    echo "rolled-back-to=$TARGET"
    echo "rollback-from=$TARGET"
    exit 0
fi

# ---------------------------------------------------------------------------
# 2. Capture the current state before changing anything.
#
# The release that failed may already have run a migration, or served writes. The
# snapshot is taken while the failed version is still the one on disk, so that if
# the rollback makes things worse there is still something to return to.
# ---------------------------------------------------------------------------
if compose_service_running "$DB_SERVICE"; then
    say "[rollback] Snapshotting the database before rolling back..."
    sh "$SCRIPT_DIR/backup.sh" mysql >/dev/null || say "[rollback] WARNING: the pre-rollback backup failed; continuing, but there is no new snapshot."
else
    say "[rollback] The database is not running; skipping the pre-rollback snapshot."
fi

# ---------------------------------------------------------------------------
# 3 and 4. Restore the exact image version, and restart the application on it.
# ---------------------------------------------------------------------------
say "[rollback] Recording $TARGET in $ENV_FILE..."
set_env_var "$MANAGED_ENV_VAR" "$TARGET_ENVIRONMENT"
set_env_var "$MANAGED_TAG_VAR" "$TARGET"

# Pull before replacing anything: if the target image is not in the registry
# (or was never pulled to this host), this fails with the failed version still
# running, rather than after it has been stopped.
say "[rollback] Pulling the image for $TARGET..."
docker compose -f "$COMPOSE_FILE" pull "$APP_SERVICE"

# `--no-build` so a deployment uses the image that was published and fails rather
# than quietly building a replacement. `--wait` blocks until the container's own
# healthcheck — which is /actuator/health — passes, so a container that starts
# and never becomes well is a failed rollback rather than a successful one that
# happens to be broken. This is the "restart and wait for health" step.
say "[rollback] Recreating the application container on $TARGET..."
docker compose -f "$COMPOSE_FILE" up -d --no-build --remove-orphans --wait "$APP_SERVICE"

# ---------------------------------------------------------------------------
# 7. Record the outcome.
# ---------------------------------------------------------------------------
DEPLOYED_SCHEMA_VERSION="$(schema_version || true)"
IMAGE_REFERENCE="$(read_env_var IMAGE_REPOSITORY || true):$TARGET"
DIGEST="$(image_digest "$IMAGE_REFERENCE" || true)"

CONTEXT_FILE="$RELEASES_DIR/$TARGET.env"
if [ -f "$CONTEXT_FILE" ]; then
    {
        echo "RELEASE_OUTCOME=deployed"
        echo "ROLLED_BACK_AT=$(utc_timestamp)"
        echo "ROLLED_BACK_FROM=${CONFIGURED_TAG:-none}"
        echo "SCHEMA_VERSION=$DEPLOYED_SCHEMA_VERSION"
        echo "IMAGE_DIGEST=$DIGEST"
    } >>"$CONTEXT_FILE"
fi

# `deployed`, not `success`: this script can prove the container started and its
# own healthcheck passed, and nothing more. The agent's health gate and smoke test
# — `scripts/ops/rollback-release.cmd` — establish that the restored version is
# actually serving, and write the `success` row. The version being restored was
# already `success` (that is why it was chosen), so a rollback run entirely by
# hand leaves the ledger's idea of "stable" exactly where it was — which is
# correct, and is why this is safe to run without an agent.
sh "$SCRIPT_DIR/release-ledger.sh" record "$TARGET" "$TARGET_ENVIRONMENT" deployed "rollback from ${CONFIGURED_TAG:-none}: $REASON"

say "[rollback] $TARGET is running in $TARGET_ENVIRONMENT."
echo "rolled-back-to=$TARGET"
echo "rollback-from=${CONFIGURED_TAG:-none}"
