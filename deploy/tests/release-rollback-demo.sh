#!/bin/sh
# ===========================================================================
# A runnable demonstration of the controlled release and rollback mechanism.
#
# What it does
#   Drives the REAL deployment scripts — deploy/deploy-on-host.sh,
#   pre-release.sh, backup.sh, release-ledger.sh and rollback-on-host.sh — through
#   the release model the brief names:
#
#       Release 1.0.0  ->  health check  ->  smoke test  ->  STABLE
#       Release 1.0.1  ->  health check  ->  smoke test FAILS
#                      ->  recorded `failed`
#                      ->  rollback to 1.0.0
#                      ->  health check  ->  smoke test  ->  STABLE
#
#   and the two verification steps use the pipeline's OWN scripts —
#   scripts/ci/wait-for-health.cmd and scripts/ops/smoke-test.cmd — against a stub
#   application, so what is demonstrated is the mechanism this repository actually
#   ships and not a description of it.
#
# What is stubbed, and what that means for what this proves
#   The Docker daemon, and the registry behind it. deploy/tests/stubs/docker
#   answers the handful of subcommands the host scripts use. Everything above the
#   daemon — the ordering, the pre-release gate, the .env rewrite, the ledger, the
#   rollback target selection, the release metadata — is the real code, unmodified.
#
#   The application itself is a stub too (deploy/tests/stub-application-server.ps1),
#   which serves health, info, a sign-in form and a guarded business route. It is
#   not the application, and this demo does not claim to test the application: the
#   unit, integration and Selenium suites do that (CLAUDE.md §6). What the stub is
#   FOR is the simulated failure — it serves /submissions unguarded for one
#   version, which is a deployment that is up, healthy, and wrong. That is the
#   failure /actuator/health cannot see, and the reason smoke-test.ps1 has a
#   business-route check.
#
#   So this WOULD be evidence about the release and rollback MECHANISM — if it were
#   run. It has never been executed: the environment this was written in refused
#   every attempt. It is a written demonstration, and running it is what would make
#   it evidence. Even then it would not be evidence that the image builds, that the
#   registry accepts a push, or that a real host can be reached over SSH.
#   docs/operations/release-runbook.md §9 and rollback.md §9 record what is
#   unproven, claim by claim.
#
# Requirements
#   A POSIX shell (Git Bash is fine) and Windows PowerShell plus cmd.exe, because
#   the verification scripts are the ones the pipeline runs. Refuses to start
#   without them rather than silently skipping the steps that matter.
#
# Environment
#   DEMO_PORT   default 18080. The port the stub application listens on.
#   DEMO_KEEP   default 1. Set to 0 to remove the sandbox after the run; it holds
#               the ledger, the .env, and the backups the run produced.
#
# Exit codes
#   0  every assertion held
#   1  one did not — the run's own output says which
# ===========================================================================

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)
DEMO_PORT="${DEMO_PORT:-18080}"
DEMO_KEEP="${DEMO_KEEP:-1}"

failures=0
step=0

banner() {
    step=$((step + 1))
    printf '\n=== %s. %s\n' "$step" "$1"
}

fail() {
    printf '\n[demo] FAILED: %s\n' "$1" >&2
    exit 1
}

check() {
    description="$1"
    expected="$2"
    actual="$3"
    if [ "$expected" = "$actual" ]; then
        printf '  PASS  %s\n' "$description"
    else
        printf '  FAIL  %s\n        expected: %s\n        actual:   %s\n' \
            "$description" "$expected" "$actual"
        failures=$((failures + 1))
    fi
}

# ---------------------------------------------------------------------------
# The environment the verification scripts need. Checked, not assumed: a demo
# that quietly skipped the health gate on a machine without PowerShell would
# report success for a release nothing verified.
# ---------------------------------------------------------------------------
command -v powershell >/dev/null 2>&1 || fail "powershell is not on PATH. The verification scripts are the ones the pipeline runs, and they need it; this demo will not skip them."
command -v cmd >/dev/null 2>&1 || fail "cmd is not on PATH. scripts/ci/wait-for-health.cmd and scripts/ops/smoke-test.cmd are .cmd scripts."

# ---------------------------------------------------------------------------
# The sandbox. Everything the run writes lives here: the deployment directory and
# its .env, the ledger, the release metadata, the backups. Nothing outside it is
# touched, which is the difference between a demo and an incident.
# ---------------------------------------------------------------------------
SANDBOX="${DEMO_SANDBOX:-${TMPDIR:-/tmp}/release-rollback-demo-$$}"
rm -rf "$SANDBOX"
mkdir -p "$SANDBOX"

export DEPLOY_DIR="$SANDBOX"
export STUB_STATE_DIR="$SANDBOX/stub-state"
export STUB_BIN_DIR="$SANDBOX/stub-bin"
mkdir -p "$STUB_STATE_DIR" "$STUB_BIN_DIR" "$SANDBOX/releases" "$SANDBOX/backups"

# The host scripts, copied rather than referenced, because that is what a release
# does: the versions that run are the ones in the tag being deployed.
for script in host-common.sh release-ledger.sh pre-release.sh backup.sh deploy-on-host.sh rollback-on-host.sh; do
    cp "$REPO_ROOT/deploy/$script" "$SANDBOX/$script"
done
cp "$REPO_ROOT/deploy/compose.prod.yaml" "$SANDBOX/compose.prod.yaml"

# The stubs, executable, at the front of PATH.
for stub in docker mysql mysqldump; do
    cp "$REPO_ROOT/deploy/tests/stubs/$stub" "$STUB_BIN_DIR/$stub"
    chmod +x "$STUB_BIN_DIR/$stub"
done
PATH="$STUB_BIN_DIR:$PATH"
export PATH

# The host's .env. A DEMO configuration: the values are obviously placeholders and
# are not credentials to anything. A real host's file is written from
# deploy/env.production.example and never from this repository (CLAUDE.md §7 rule
# 1, §8).
cat >"$SANDBOX/.env" <<'ENV'
DB_NAME=assignment_portal
DB_USERNAME=portal
DB_PASSWORD=demo-placeholder-not-a-credential
DB_ROOT_PASSWORD=demo-placeholder-not-a-credential
IMAGE_REPOSITORY=demo/online-assignment-portal
APP_IMAGE_TAG=
APP_ENVIRONMENT=production
APP_HOST_PORT=8080
ENV

export MYSQL_ROOT_PASSWORD=demo-placeholder-not-a-credential
export MYSQL_DATABASE=assignment_portal

# The stub application. UnguardedVersions=1.0.1 is THE simulated failure: for that
# version the business route answers an anonymous caller instead of refusing it.
VERSION_FILE="$STUB_STATE_DIR/running-version"
printf '%s' '' >"$VERSION_FILE"

powershell -NoProfile -ExecutionPolicy Bypass \
    -File "$SCRIPT_DIR/stub-application-server.ps1" \
    -Port "$DEMO_PORT" \
    -VersionFile "$(cygpath -w "$VERSION_FILE" 2>/dev/null || printf '%s' "$VERSION_FILE")" \
    -Environment production \
    -UnguardedVersions 1.0.1 \
    -StopAfter 600 &
SERVER_PID=$!

stop_server() {
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
}
trap stop_server EXIT

# ---------------------------------------------------------------------------
# Helpers that run the scripts under test.
# ---------------------------------------------------------------------------

# Run a host-side script in the sandbox and return its exit code, without letting
# `set -e` end the demo: several steps are EXPECTED to fail.
run_host() {
    ( cd "$SANDBOX" && sh "$@" ) 2>&1
}

run_script_expecting_success() {
    description="$1"
    shift
    printf '\n$ %s\n' "$*"
    if output=$(run_host "$@"); then
        printf '%s\n' "$output"
    else
        printf '%s\n' "$output"
        fail "$description"
    fi
}

# The two verification steps, run as the pipeline runs them. This is the part that
# is NOT simulated: scripts/ci/wait-for-health.cmd and scripts/ops/smoke-test.cmd.
verify_deployment() {
    version="$1"
    export DEPLOY_BASE_URL="http://127.0.0.1:$DEMO_PORT"
    export DEPLOY_ENV=production
    export RELEASE_VERSION="$version"
    export HEALTH_URL="$DEPLOY_BASE_URL/actuator/health"
    export HEALTH_TIMEOUT_SECONDS=30
    export APP_PORT="$DEMO_PORT"

    printf '\n$ scripts\\ci\\wait-for-health.cmd\n'
    if ! ( cd "$REPO_ROOT" && cmd //c "scripts\\ci\\wait-for-health.cmd" ); then
        return 1
    fi

    printf '\n$ scripts\\ops\\smoke-test.cmd\n'
    if ! ( cd "$REPO_ROOT" && cmd //c "scripts\\ops\\smoke-test.cmd" ); then
        return 1
    fi
}

ledger_last_stable() {
    ( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh release-ledger.sh last-stable )
}

configured_tag() {
    sed -n 's/^APP_IMAGE_TAG=\(.*\)$/\1/p' "$SANDBOX/.env" | head -n 1
}

# ---------------------------------------------------------------------------
printf '========================================================================\n'
printf 'Release and rollback demonstration\n'
printf '  repository : %s\n' "$REPO_ROOT"
printf '  sandbox    : %s\n' "$SANDBOX"
printf '  port       : %s\n' "$DEMO_PORT"
printf '========================================================================\n'

# ---------------------------------------------------------------------------
banner "Wait for the stub application to answer"
# ---------------------------------------------------------------------------
attempt=0
while [ "$attempt" -lt 30 ]; do
    status=$(powershell -NoProfile -Command \
        "try { (Invoke-RestMethod -Uri 'http://127.0.0.1:$DEMO_PORT/actuator/health' -TimeoutSec 2).status } catch { '' }" \
        2>/dev/null | tr -d '\r')
    [ "$status" = "UP" ] && break
    attempt=$((attempt + 1))
    sleep 1
done
[ "$status" = "UP" ] || fail "the stub application never answered on port $DEMO_PORT."
printf '  the stub application is UP on 127.0.0.1:%s\n' "$DEMO_PORT"

# ---------------------------------------------------------------------------
banner "Release 1.0.0"
# ---------------------------------------------------------------------------
run_script_expecting_success "the first release should have started" \
    deploy-on-host.sh deploy 1.0.0 production

printf '\n  the host recorded the release as `deployed`; the ledger now reads:\n'
( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh release-ledger.sh history | sed 's/^/    /' )

printf '\n  the pre-release gate also left a release record and a backup:\n'
ls -1 "$SANDBOX/releases" | sed 's/^/    releases\//'
ls -1 "$SANDBOX/backups" | sed 's/^/    backups\//'

# ---------------------------------------------------------------------------
banner "Verify 1.0.0 from outside the deployment"
# ---------------------------------------------------------------------------
verify_deployment 1.0.0 || fail "1.0.0 did not verify. Nothing after this point means anything."

# What scripts/ops/deploy-release.cmd does once both checks pass.
( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh release-ledger.sh record 1.0.0 production success "verified from the agent" >/dev/null )
printf '\n  the agent recorded 1.0.0 as `success`. 1.0.0 is now STABLE.\n'

check "the ledger's last stable version is 1.0.0" "1.0.0" "$(ledger_last_stable)"
check ".env is configured for 1.0.0" "1.0.0" "$(configured_tag)"

# ---------------------------------------------------------------------------
banner "Release 1.0.1, which is broken in a way health cannot see"
# ---------------------------------------------------------------------------
run_script_expecting_success "1.0.1 should have started (the regression is not a startup failure)" \
    deploy-on-host.sh deploy 1.0.1 production

printf '\n  the host recorded 1.0.1 as `deployed` — its container came up and its own\n'
printf '  healthcheck passed. That is exactly the trap this design exists to avoid:\n'
check ".env now names 1.0.1, the version that is about to fail" "1.0.1" "$(configured_tag)"
check "the ledger's last STABLE version is still 1.0.0" "1.0.0" "$(ledger_last_stable)"

# ---------------------------------------------------------------------------
banner "Verify 1.0.1 — the health check passes and the smoke test fails"
# ---------------------------------------------------------------------------
export DEPLOY_BASE_URL="http://127.0.0.1:$DEMO_PORT"
export DEPLOY_ENV=production
export RELEASE_VERSION=1.0.1
export HEALTH_URL="$DEPLOY_BASE_URL/actuator/health"
export HEALTH_TIMEOUT_SECONDS=30
export APP_PORT="$DEMO_PORT"

set +e
verify_deployment 1.0.1 >"$SANDBOX/verify-1.0.1.log" 2>&1
verify_status=$?
set -e
sed 's/^/    /' "$SANDBOX/verify-1.0.1.log"

check "the verification of 1.0.1 fails" "1" "$verify_status"

printf '\n  the failure is the business-route check, and only that:\n'
health_passes=$(grep -c 'the health endpoint reports UP' "$SANDBOX/verify-1.0.1.log" || true)
smoke_failures=$(grep -c 'failed checks: .*business-route-guard' "$REPO_ROOT/target/smoke-test.txt" || true)
check "the health check passed" "1" "$health_passes"
check "the smoke report names business-route-guard as the failure" "1" "$smoke_failures"

# What scripts/ops/deploy-release.cmd's :verify_failed branch does before rolling back.
( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh release-ledger.sh record 1.0.1 production failed "did not verify from the agent" >/dev/null )
printf '\n  the agent corrected the ledger: 1.0.1 is `failed`, not `deployed`.\n'

# ---------------------------------------------------------------------------
banner "Identify the version to return to"
# ---------------------------------------------------------------------------
printf '\n$ sh rollback-on-host.sh plan\n'
( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh rollback-on-host.sh plan ) 2>&1 | sed 's/^/    /'

plan=$( ( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh rollback-on-host.sh plan ) 2>/dev/null )
check "the plan names 1.0.0" "target=1.0.0" "$(printf '%s\n' "$plan" | grep '^target=')"

# ---------------------------------------------------------------------------
banner "Roll back to 1.0.0"
# ---------------------------------------------------------------------------
run_script_expecting_success "the rollback should have restored 1.0.0" \
    rollback-on-host.sh rollback

check ".env is configured for 1.0.0 again" "1.0.0" "$(configured_tag)"

printf '\n  the ledger now shows the whole story:\n'
( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh release-ledger.sh history | sed 's/^/    /' )

printf '\n  the rollback took its own snapshot before changing anything:\n'
ls -1 "$SANDBOX/backups" | sed 's/^/    backups\//'

# ---------------------------------------------------------------------------
banner "Verify 1.0.0 again"
# ---------------------------------------------------------------------------
verify_deployment 1.0.0 || fail "the restored 1.0.0 did not verify."

( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh release-ledger.sh record 1.0.0 production success "verified from the agent after rollback" >/dev/null )
check "the ledger's last stable version is 1.0.0" "1.0.0" "$(ledger_last_stable)"

# ---------------------------------------------------------------------------
banner "Result"
# ---------------------------------------------------------------------------
printf '\n  ledger (%s):\n' "$SANDBOX/releases/ledger.tsv"
( cd "$SANDBOX" && DEPLOY_DIR="$SANDBOX" sh release-ledger.sh history | sed 's/^/    /' )

printf '\n  the release metadata the run left behind:\n'
for file in "$SANDBOX"/releases/*.env; do
    printf '    %s\n' "$(basename "$file")"
    sed 's/^/      /' "$file"
done

[ "$DEMO_KEEP" = "1" ] || rm -rf "$SANDBOX"

printf '\n========================================================================\n'
if [ "$failures" -eq 0 ]; then
    printf 'DEMO PASSED — every assertion held.\n'
    printf 'The sandbox is at %s\n' "$SANDBOX"
    printf '========================================================================\n'
    exit 0
fi

printf 'DEMO FAILED — %s assertion(s) did not hold.\n' "$failures"
printf 'The sandbox is at %s\n' "$SANDBOX"
printf '========================================================================\n'
exit 1
