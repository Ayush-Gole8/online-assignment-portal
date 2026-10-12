#!/bin/sh
# ===========================================================================
# Shared definitions for the host-side deployment scripts.
#
# SOURCED, never executed. `. "$(dirname "$0")/host-common.sh"` at the top of a
# host script, after its own `set -eu`. It defines variables and functions and
# does nothing else — it does not `cd`, it does not read a file, and it does not
# touch the system — so sourcing it can never be the reason a script failed
# before it started.
#
# Why a shared file rather than a copy of twelve lines in each script
#   `set_env_var` is the one operation that rewrites the host's `.env`, and it is
#   subtle: it must rewrite in place rather than append, it must not disturb a
#   credential on any other line, and both callers must validate their argument
#   first because the value reaches `sed` unquoted. Three copies of it would be
#   three chances for the copies to disagree, and the copy that drifted would be
#   the one that corrupted a host's configuration during a release. This is the
#   same reasoning `scripts/ci/env.cmd` records for itself, applied to the
#   deployment host.
#
# Where it lives
#   Beside compose.prod.yaml and the deploy scripts in the deployment directory.
#   `scripts/ops/deploy-release.cmd` and `scripts/ops/rollback-release.cmd` copy
#   the whole set on every release, so the host always runs the versions that
#   were reviewed alongside the code they support.
# ===========================================================================

# ---------------------------------------------------------------------------
# The deployment directory, and the files in it.
# ---------------------------------------------------------------------------

# Overridable, so a second environment can live on one host under its own path.
DEPLOY_DIR="${DEPLOY_DIR:-/opt/assignment-portal}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.prod.yaml}"
ENV_FILE="${ENV_FILE:-.env}"

# Where the release record and the backups live. Both are on the HOST's own
# filesystem and deliberately outside Docker's storage root, so that a
# `docker system prune -a --volumes` cannot take the release history — or a
# database dump — with it. The Ansible `application` role creates both with mode
# 0750 (a dump is a copy of every submission record).
RELEASES_DIR="${RELEASES_DIR:-$DEPLOY_DIR/releases}"
BACKUPS_DIR="${BACKUPS_DIR:-$DEPLOY_DIR/backups}"

# The release ledger: the append-only record of every release attempt. This is
# the file that lets a rollback name the last known stable version instead of
# guessing, and it exists because `.env` alone cannot answer the question — a
# failed release rewrites `APP_IMAGE_TAG` before it fails, so `.env` reports the
# version that is broken, not the version that worked. See release-ledger.sh.
LEDGER_FILE="${LEDGER_FILE:-$RELEASES_DIR/ledger.tsv}"

# The two variables in `.env` this project's deployment tooling owns. Everything
# else in that file belongs to the host and is never rewritten.
MANAGED_TAG_VAR="APP_IMAGE_TAG"
MANAGED_ENV_VAR="APP_ENVIRONMENT"

# ---------------------------------------------------------------------------
# The running system, named once.
# ---------------------------------------------------------------------------

DB_SERVICE="db"
APP_SERVICE="app"

# The named volumes the stack requires, exactly as deploy/compose.prod.yaml
# declares them. A missing one is data loss, which is why pre-release.sh refuses
# to deploy when a host that has released before has lost one.
REQUIRED_VOLUMES="assignment-portal-mysql-data assignment-portal-uploads"

# ---------------------------------------------------------------------------
# Output and failure
# ---------------------------------------------------------------------------

# Everything this tooling prints for a human goes to stderr; stdout carries only
# the machine-readable lines the agent parses (`previous=`, `rolled-back-to=`).
# That separation is what keeps a status sentence from ever being mistaken for a
# result.
say() {
    echo "$@" >&2
}

fail() {
    echo "[host] FAILED: $*" >&2
    exit "${FAIL_CODE:-1}"
}

# ---------------------------------------------------------------------------
# Time
# ---------------------------------------------------------------------------

# UTC, ISO-8601, second resolution. Every host record is written in UTC so that a
# ledger kept across a rollback is ordered by one clock, not by whichever host
# happened to write a line.
utc_timestamp() {
    date -u +%Y-%m-%dT%H:%M:%SZ
}

# ---------------------------------------------------------------------------
# The environment file
# ---------------------------------------------------------------------------

# Read one `KEY=` value from `.env`. Prints nothing when the file or the key is
# absent, and never fails: a caller that requires the value checks for emptiness
# itself, so that "not set" and "reading it failed" are not the same code path.
read_env_var() {
    key="$1"
    [ -f "$ENV_FILE" ] || return 0
    sed -n "s|^${key}=\(.*\)|\1|p" "$ENV_FILE" | head -n 1
}

# Replace `KEY=...` in `.env`, or append it when absent.
#
# A rewrite rather than an append-with-new-value, so the file a human edits and
# the file a release writes stay the same file and do not grow a line per
# release. The value is written unquoted, which is exactly why both callers
# validate their argument first: neither a version nor an environment name may
# contain a character that means something to sed or to the dotenv reader.
set_env_var() {
    key="$1"
    value="$2"
    if [ ! -f "$ENV_FILE" ]; then
        fail "$DEPLOY_DIR/$ENV_FILE does not exist. Copy deploy/env.production.example to the host and fill it in; this tooling will not create a configuration file it does not understand."
    fi
    if grep -q "^${key}=" "$ENV_FILE"; then
        sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
    else
        printf '%s=%s\n' "$key" "$value" >>"$ENV_FILE"
    fi
}

# ---------------------------------------------------------------------------
# Argument validation
# ---------------------------------------------------------------------------

# A release version is the git tag with its leading `v` stripped: three
# dot-separated numbers. Validated rather than trusted, because the value reaches
# `sed` and becomes an image tag — `latest` is never one of them (ADR-006).
require_version() {
    version="$1"
    label="${2:-version}"
    if ! printf '%s' "$version" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
        FAIL_CODE=2 fail "'$version' is not a release $label. Expected three dot-separated numbers, such as 1.0.1 — the git tag v1.0.1 without its leading v."
    fi
}

# A deployable environment is one of two words. `none` means the release was
# published and deliberately not deployed, which is a decision the pipeline makes
# before this tooling is reached; arriving here with it is a defect.
require_environment() {
    environment="$1"
    case "$environment" in
        staging | production) ;;
        *) FAIL_CODE=2 fail "'$environment' is not a deployable environment. Expected 'staging' or 'production'." ;;
    esac
}

# ---------------------------------------------------------------------------
# Docker helpers
# ---------------------------------------------------------------------------

# The container id of a Compose service, running or stopped. Empty when the
# service has never been created.
compose_container_id() {
    docker compose -f "$COMPOSE_FILE" ps -q "$1" 2>/dev/null || true
}

# True when the service has a container and that container is running.
compose_service_running() {
    cid="$(compose_container_id "$1")"
    [ -n "$cid" ] || return 1
    [ "$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null || echo false)" = "true" ]
}

# The Flyway schema version the database is at, read from the history table
# Flyway itself maintains. Recorded with every release so that a later rollback
# can reason about whether the schema moved — and ONLY recorded. Nothing in this
# repository ever reverses a migration (ADR-003).
schema_version() {
    docker compose -f "$COMPOSE_FILE" exec -T "$DB_SERVICE" sh -c '
        MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root --batch --skip-column-names \
            "$MYSQL_DATABASE" \
            -e "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1"
    ' 2>/dev/null | tr -d '\r' | head -n 1 || true
}

# The digest of the deployed image, as the registry knows it. A tag can be
# re-pointed upstream; a digest cannot, which is why a release records one.
image_digest() {
    reference="$1"
    docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$reference" 2>/dev/null \
        | head -n 1 || true
}
