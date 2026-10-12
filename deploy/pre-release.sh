#!/bin/sh
# ===========================================================================
# The pre-release gate — everything a release must establish BEFORE it replaces
# the running container.
#
# Runs ON the host, called by deploy-on-host.sh at the start of every deploy, and
# runnable by hand. Its whole purpose is to fail EARLY: a release that cannot be
# backed up, or whose volumes have gone missing, must not get far enough to
# replace a container that is currently serving.
#
# The four things it does, in order, and why each comes before the swap
#   1. Capture the CURRENT image version — what `.env` says the host is running,
#      recorded before anything is rewritten so the deployment report can name it.
#   2. Record the PREVIOUS STABLE version — the ledger's last success, which is
#      what a rollback will return to. Read now because the swap is about to make
#      `.env` disagree with it.
#   3. Verify the required persistent volumes exist. A named volume that has
#      vanished is not a configuration problem; it is lost submissions. If the
#      host has released before and a volume is now missing, this refuses.
#   4. Take a MySQL backup. The one thing a bad release cannot be undone from
#      cheaply is a data change, and this is the snapshot that makes the rest of
#      the runbook's recovery path possible.
#
# It writes releases/<version>.env — the release's metadata — on the way through.
# deploy-on-host.sh appends the post-deploy facts (schema version, image digest,
# outcome) to the same file after a successful start. See release-runbook.md §4.
#
# Usage
#   sh pre-release.sh <version> <environment>
#
# Environment
#   ALLOW_RELEASE_WITHOUT_BACKUP=1   # proceed when the database is not running
#                                    # and there is no dump to take. Off by
#                                    # default: releasing over an unreachable
#                                    # database is a decision, not a default.
#
# Exit codes
#   0  the gate passed and a backup exists (or there was genuinely nothing to back up)
#   2  the arguments were refused
#   1  the gate refused — a missing volume, or a backup that could not be taken
# ===========================================================================

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=deploy/host-common.sh
. "$SCRIPT_DIR/host-common.sh"

RELEASE_VERSION="${1:-}"
RELEASE_ENVIRONMENT="${2:-}"

require_version "$RELEASE_VERSION"
require_environment "$RELEASE_ENVIRONMENT"

cd "$DEPLOY_DIR" || fail "cannot enter $DEPLOY_DIR"

[ -d "$RELEASES_DIR" ] || mkdir -p "$RELEASES_DIR" || fail "cannot create $RELEASES_DIR"

# --- 1. The version the host is configured for right now ---------------------
CONFIGURED_TAG="$(read_env_var "$MANAGED_TAG_VAR" || true)"

# --- 2. The last known stable version, from the ledger -----------------------
PREVIOUS_STABLE="$(sh "$SCRIPT_DIR/release-ledger.sh" last-stable || true)"

say "[pre-release] Deploying $RELEASE_VERSION to $RELEASE_ENVIRONMENT."
say "[pre-release] Currently configured: ${CONFIGURED_TAG:-<none>}"
say "[pre-release] Last known stable:    ${PREVIOUS_STABLE:-<none>}"
echo "previous-stable=$PREVIOUS_STABLE"

# --- 3. The volumes ----------------------------------------------------------
# A volume that does not exist yet is the first-release case: `docker compose up`
# will create it, and refusing here would make a first deployment impossible.
# A volume that does not exist on a host that has SUCCEEDED before is data loss —
# and a deployment that proceeded would create an empty one in its place and start
# against a database with no rows, which is the quietest possible way to lose
# every submission. So the two cases are told apart by whether the ledger holds a
# success, and only one of them is fatal.
MISSING_VOLUMES=""
for volume in $REQUIRED_VOLUMES; do
    if ! docker volume inspect "$volume" >/dev/null 2>&1; then
        MISSING_VOLUMES="$MISSING_VOLUMES $volume"
    fi
done

if [ -n "$MISSING_VOLUMES" ]; then
    if [ -n "$PREVIOUS_STABLE" ]; then
        fail "required volume(s)$MISSING_VOLUMES do not exist, but this host has already delivered $PREVIOUS_STABLE. A named volume that has vanished is lost data, not missing configuration; refusing to deploy over it. Investigate the Docker daemon and the host's disk before continuing."
    fi
    say "[pre-release] NOTE: volume(s)$MISSING_VOLUMES do not exist yet. This is the first release to this host; docker compose will create them."
else
    say "[pre-release] All required volumes are present: $REQUIRED_VOLUMES"
fi

# --- 4. The backup -----------------------------------------------------------
BACKUP_FILE="none"

if compose_service_running "$DB_SERVICE"; then
    BACKUP_FILE="$(sh "$SCRIPT_DIR/backup.sh" mysql)"
    say "[pre-release] Backup written: $BACKUP_FILE"
elif [ -n "$PREVIOUS_STABLE" ]; then
    if [ "${ALLOW_RELEASE_WITHOUT_BACKUP:-0}" != "1" ]; then
        fail "the '$DB_SERVICE' container is not running, so no backup can be taken, and this host has already delivered $PREVIOUS_STABLE — so there is data to lose. Start the stack and retry, or set ALLOW_RELEASE_WITHOUT_BACKUP=1 to accept releasing without a snapshot."
    fi
    say "[pre-release] WARNING: proceeding without a backup because ALLOW_RELEASE_WITHOUT_BACKUP=1."
else
    say "[pre-release] The database is not running and this is the first release; there is nothing to back up."
fi

# --- The release's metadata --------------------------------------------------
# Written before the swap so that a release which never starts still leaves a
# record of what was attempted. deploy-on-host.sh appends the outcome to it.
CONTEXT_FILE="$RELEASES_DIR/$RELEASE_VERSION.env"
{
    echo "RELEASE_VERSION=$RELEASE_VERSION"
    echo "RELEASE_ENVIRONMENT=$RELEASE_ENVIRONMENT"
    echo "RELEASE_STARTED_AT=$(utc_timestamp)"
    echo "IMAGE_REPOSITORY=$(read_env_var IMAGE_REPOSITORY || true)"
    echo "IMAGE_TAG=$RELEASE_VERSION"
    echo "PREVIOUS_CONFIGURED_TAG=$CONFIGURED_TAG"
    echo "PREVIOUS_STABLE_VERSION=$PREVIOUS_STABLE"
    echo "BACKUP_FILE=$BACKUP_FILE"
} >"$CONTEXT_FILE"

say "[pre-release] Release metadata written to $CONTEXT_FILE"
echo "release-context=$CONTEXT_FILE"
