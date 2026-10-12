#!/bin/sh
# ===========================================================================
# The host-side half of a release. Runs ON the deployment host, over SSH,
# driven by scripts/ops/deploy-release.cmd on the Jenkins agent.
#
# Why this is a file in the repository and not a quoted one-liner
#   The sequence it performs — establish the pre-release facts, record the
#   version currently running, rewrite two lines of configuration, pull an exact
#   tag, replace the application container, record the outcome — is the part of a
#   deployment that must be reproducible. A sequence that exists only as an
#   argument in a Jenkinsfile cannot be read, reviewed, or run by hand while a
#   deployment is going wrong, which is the moment it is needed. It is copied
#   here by the deploy script on every release, so what runs on the host is what
#   is in the tag being deployed.
#
# Where the other pieces live
#   host-common.sh   the shared definitions (this file, pre-release.sh,
#                    release-ledger.sh, backup.sh, rollback-on-host.sh)
#   pre-release.sh   the gate: volumes, backup, and the release's metadata
#   release-ledger.sh the append-only record of which versions actually worked
#   rollback-on-host.sh the way back, and the operational rollback command
#
# What it deliberately does NOT do
#   It does not know any credential. The database password, the registry
#   credential for pulling, and the image repository all live in the host's
#   `.env`, which this script reads and rewrites in exactly two places and
#   otherwise leaves alone. Jenkins never sees any of them.
#
#   It does not decide whether a deployment succeeded. It starts the stack and
#   waits for the container's own healthcheck; the agent then polls the same
#   endpoint over the network and runs the smoke test, because "the container
#   says it is well" and "it answers requests from elsewhere" are different
#   claims and a deployment has to establish both.
#
# Usage
#   sh deploy-on-host.sh show-previous
#       Prints the version the ledger says was last known stable, or — when the
#       ledger is empty — whatever `.env` is configured for. Always exits 0.
#
#   sh deploy-on-host.sh deploy <version> <environment>
#       Runs the pre-release gate, records <version> and <environment> in `.env`,
#       pulls that exact image, replaces the application container, and records
#       the outcome in the ledger. Prints `previous=<version>` on the first line,
#       so the caller can roll back without a second round trip, and appends the
#       schema version and the image digest to releases/<version>.env.
#
# Exit codes
#   0  the container was started and its own healthcheck reports healthy
#   2  the arguments were refused (bad version, unknown environment)
#   1  anything else — a missing file, a refused pre-release gate, a failed pull,
#      a container that did not become healthy. The output names which.
# ===========================================================================

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=deploy/host-common.sh
. "$SCRIPT_DIR/host-common.sh"

cd "$DEPLOY_DIR" || fail "cannot enter $DEPLOY_DIR"

command -v docker >/dev/null 2>&1 || fail "docker is not on PATH on this host."

if [ ! -f "$COMPOSE_FILE" ]; then
    fail "$DEPLOY_DIR/$COMPOSE_FILE does not exist. The deploy script copies it here on every release; a host without one has never been deployed to."
fi

# The version the host is configured for right now, read before anything is
# rewritten. It is a fallback for the rollback target, not the target itself:
# the ledger is authoritative when it has anything to say, because `.env` is
# edited before it is known whether the release works.
CONFIGURED_TAG="$(read_env_var "$MANAGED_TAG_VAR" || true)"

record_failure() {
    # Records a failed attempt in the ledger, once. Called from the EXIT trap so
    # that a failure at ANY step — the gate, the pull, the container start —
    # leaves the same mark, and so that a release that never starts is still a
    # row a later rollback can reason about.
    [ "${OUTCOME_RECORDED:-0}" = "1" ] && return 0
    sh "$SCRIPT_DIR/release-ledger.sh" record "$1" "$2" failed "${3:-unknown}" >/dev/null 2>&1 || true
}

MODE="${1:-}"

case "$MODE" in
    show-previous)
        say "[deploy-on-host] Reading the version this host is on..."
        stable="$(sh "$SCRIPT_DIR/release-ledger.sh" last-stable || true)"
        if [ -n "$stable" ]; then
            echo "$stable"
        else
            echo "$CONFIGURED_TAG"
        fi
        exit 0
        ;;

    deploy)
        RELEASE_VERSION="${2:-}"
        DEPLOY_ENV="${3:-}"

        # The version reaches sed and becomes an image tag, so it is validated
        # here rather than trusted. It is the git tag with the leading `v`
        # stripped (Jenkinsfile, Release Version stage).
        require_version "$RELEASE_VERSION"
        require_environment "$DEPLOY_ENV"

        # The rollback target: the last version that RECORDED a success. The
        # ledger is preferred over `.env` because a release rewrites `.env` before
        # it can fail; `.env` is used only when the ledger has nothing (a host
        # provisioned by Ansible, whose image tag was set by the playbook and
        # never went through this pipeline), and only when it names a version.
        PREVIOUS_VERSION="$(sh "$SCRIPT_DIR/release-ledger.sh" last-stable || true)"
        if [ -z "$PREVIOUS_VERSION" ] && printf '%s' "$CONFIGURED_TAG" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
            PREVIOUS_VERSION="$CONFIGURED_TAG"
        fi
        echo "previous=$PREVIOUS_VERSION"

        # Failures from here on are the release's own, and are recorded as such.
        # The trap is armed only once the arguments are known good, so a refused
        # argument does not write a ledger row.
        trap 'record_failure "$RELEASE_VERSION" "$DEPLOY_ENV" "$LAST_STEP"' EXIT

        LAST_STEP="pre-release gate"
        sh "$SCRIPT_DIR/pre-release.sh" "$RELEASE_VERSION" "$DEPLOY_ENV"

        LAST_STEP="configuration rewrite"
        say "[deploy-on-host] Recording release $RELEASE_VERSION for environment $DEPLOY_ENV..."
        set_env_var "$MANAGED_ENV_VAR" "$DEPLOY_ENV"
        set_env_var "$MANAGED_TAG_VAR" "$RELEASE_VERSION"

        # Pull before replacing anything. This is the step that proves the image
        # exists: if the tag was never pushed, or was pushed under a different
        # name, this fails here — with the old container still running and
        # serving traffic — rather than after the old one has been stopped.
        LAST_STEP="image pull"
        say "[deploy-on-host] Pulling the image for $RELEASE_VERSION..."
        docker compose -f "$COMPOSE_FILE" pull "$APP_SERVICE"

        # --no-build is the point of this file. It makes the deployment use the
        # image that was pushed, and fail rather than quietly build a
        # replacement if no such image can be pulled.
        #
        # --wait blocks until every service with a healthcheck is healthy, so a
        # container that starts and never becomes well is a failed deployment
        # rather than a successful one that happens to be broken.
        LAST_STEP="container replacement"
        say "[deploy-on-host] Replacing the application container..."
        docker compose -f "$COMPOSE_FILE" up -d --no-build --remove-orphans --wait "$APP_SERVICE"

        # ------------------------------------------------------------------
        # The container came up. Record that, and finish the release metadata
        # with the facts that only exist once it is running: the schema version
        # Flyway has migrated to, and the digest of the image that is running.
        #
        # The outcome is `deployed`, NOT `success`. The container's own
        # healthcheck passing proves the process started; it does not prove the
        # release answers requests from another machine, which is the claim the
        # agent's health gate and smoke test establish. `success` — the outcome
        # that makes a version STABLE and therefore a rollback target — is
        # written by scripts/ops/deploy-release.cmd after both of those pass. A
        # host that recorded success on its own would call a version good that
        # the agent is about to find broken and roll back.
        # ------------------------------------------------------------------
        LAST_STEP="outcome recording"
        SCHEMA_VERSION="$(schema_version || true)"
        DIGEST="$(image_digest "$(read_env_var IMAGE_REPOSITORY || true):$RELEASE_VERSION" || true)"

        CONTEXT_FILE="$RELEASES_DIR/$RELEASE_VERSION.env"
        if [ -f "$CONTEXT_FILE" ]; then
            {
                echo "RELEASE_OUTCOME=deployed"
                echo "RELEASE_CONTAINER_STARTED_AT=$(utc_timestamp)"
                echo "SCHEMA_VERSION=$SCHEMA_VERSION"
                echo "IMAGE_DIGEST=$DIGEST"
            } >>"$CONTEXT_FILE"
        fi

        sh "$SCRIPT_DIR/release-ledger.sh" record "$RELEASE_VERSION" "$DEPLOY_ENV" deployed ""
        OUTCOME_RECORDED=1

        say "[deploy-on-host] $RELEASE_VERSION is running in $DEPLOY_ENV."
        exit 0
        ;;

    *)
        {
            echo "Usage: sh deploy-on-host.sh show-previous"
            echo "       sh deploy-on-host.sh deploy <version> <environment>"
        } >&2
        exit 2
        ;;
esac
