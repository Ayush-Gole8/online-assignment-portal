#!/bin/sh
# ===========================================================================
# The host-side half of a release. Runs ON the deployment host, over SSH,
# driven by scripts/ops/deploy-release.cmd on the Jenkins agent.
#
# Why this is a file in the repository and not a quoted one-liner
#   The sequence it performs — record the version currently running, rewrite two
#   lines of configuration, pull an exact tag, replace the application container
#   — is the part of a deployment that must be reproducible. A sequence that
#   exists only as an argument in a Jenkinsfile cannot be read, reviewed, or run
#   by hand while a deployment is going wrong, which is the moment it is needed.
#   It is copied here by the deploy script on every release, so what runs on the
#   host is what is in the tag being deployed.
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
#       Prints the version this host is currently configured for. Prints an
#       empty line when it has never been deployed. Always exits 0.
#
#   sh deploy-on-host.sh deploy <version> <environment>
#       Records <version> and <environment> in `.env`, pulls that exact image,
#       and replaces the application container. Prints `previous=<version>` on
#       the first line, so the caller can roll back without a second round trip.
#
# Exit codes
#   0  the container was started and its own healthcheck reports healthy
#   2  the arguments were refused (bad version, unknown environment)
#   1  anything else — a missing file, a failed pull, a container that did not
#      become healthy. The compose output explains which.
# ===========================================================================

set -eu

# The directory holding compose.prod.yaml and .env. Overridable so that a second
# environment can live on the same host under its own path.
DEPLOY_DIR="${DEPLOY_DIR:-/opt/assignment-portal}"
COMPOSE_FILE="compose.prod.yaml"
ENV_FILE=".env"

# The two variables this script owns in `.env`. Everything else in that file
# belongs to the host and is never touched.
MANAGED_TAG_VAR="APP_IMAGE_TAG"
MANAGED_ENV_VAR="APP_ENVIRONMENT"

fail() {
    echo "[deploy-on-host] FAILED: $*" >&2
    exit "${2:-1}"
}

# Replace `KEY=...` in the environment file, or append it when absent.
#
# A rewrite rather than an append-with-new-value, so that `.env` does not grow a
# new line on every release and so that the file a human edits and the file the
# release writes are the same file. The value is written unquoted, which is why
# both callers validate their argument first: neither a version nor an
# environment name may contain a character that means something to sed or to the
# dotenv reader.
set_env_var() {
    key="$1"
    value="$2"
    if [ ! -f "$ENV_FILE" ]; then
        fail "$DEPLOY_DIR/$ENV_FILE does not exist. Copy deploy/env.production.example to the host and fill it in; this script will not create a configuration file it does not understand."
    fi
    if grep -q "^${key}=" "$ENV_FILE"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
    else
        printf '%s=%s\n' "$key" "$value" >>"$ENV_FILE"
    fi
}

read_env_var() {
    key="$1"
    if [ -f "$ENV_FILE" ]; then
        sed -n "s|^${key}=\(.*\)|\1|p" "$ENV_FILE" | head -n 1
    fi
}

cd "$DEPLOY_DIR" || fail "cannot enter $DEPLOY_DIR"

command -v docker >/dev/null 2>&1 || fail "docker is not on PATH on this host."

if [ ! -f "$COMPOSE_FILE" ]; then
    fail "$DEPLOY_DIR/$COMPOSE_FILE does not exist. The deploy script copies it here on every release; a host without one has never been deployed to."
fi

MODE="${1:-}"

case "$MODE" in
    show-previous)
        echo "[deploy-on-host] Reading the version this host currently runs..." >&2
        read_env_var "$MANAGED_TAG_VAR" || true
        exit 0
        ;;

    deploy)
        RELEASE_VERSION="${2:-}"
        DEPLOY_ENV="${3:-}"

        # The version reaches sed and becomes an image tag, so it is validated
        # here rather than trusted. It is the git tag with the leading `v`
        # stripped (Jenkinsfile, Release Version stage).
        if ! printf '%s' "$RELEASE_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
            fail "'$RELEASE_VERSION' is not a release version. Expected three dot-separated numbers, such as 1.0.1 — the git tag v1.0.1 without its leading v." 2
        fi

        case "$DEPLOY_ENV" in
            staging|production) ;;
            *) fail "'$DEPLOY_ENV' is not a deployable environment. Expected 'staging' or 'production'; 'none' means the release was published and deliberately not deployed." 2 ;;
        esac

        PREVIOUS_VERSION="$(read_env_var "$MANAGED_TAG_VAR" || true)"
        echo "previous=$PREVIOUS_VERSION"

        echo "[deploy-on-host] Recording release $RELEASE_VERSION for environment $DEPLOY_ENV..."
        set_env_var "$MANAGED_ENV_VAR" "$DEPLOY_ENV"
        set_env_var "$MANAGED_TAG_VAR" "$RELEASE_VERSION"

        # Pull before replacing anything. This is the step that proves the image
        # exists: if the tag was never pushed, or was pushed under a different
        # name, this fails here — with the old container still running and
        # serving traffic — rather than after the old one has been stopped.
        echo "[deploy-on-host] Pulling the image for $RELEASE_VERSION..."
        docker compose -f "$COMPOSE_FILE" pull app

        # --no-build is the point of this file. It makes the deployment use the
        # image that was pushed, and fail rather than quietly build a
        # replacement if no such image can be pulled.
        #
        # --wait blocks until every service with a healthcheck is healthy, so a
        # container that starts and never becomes well is a failed deployment
        # rather than a successful one that happens to be broken.
        echo "[deploy-on-host] Replacing the application container..."
        docker compose -f "$COMPOSE_FILE" up -d --no-build --remove-orphans --wait app

        echo "[deploy-on-host] $RELEASE_VERSION is running in $DEPLOY_ENV."
        exit 0
        ;;

    *)
        echo "Usage: sh deploy-on-host.sh show-previous" >&2
        echo "       sh deploy-on-host.sh deploy <version> <environment>" >&2
        exit 2
        ;;
esac
