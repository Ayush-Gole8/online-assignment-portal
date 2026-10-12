#!/bin/sh
# ===========================================================================
# The release ledger — the record that lets a rollback name the previous version
# without guesswork.
#
# SOURCED BY NOTHING. Run as a command, on the host, by deploy-on-host.sh,
# pre-release.sh, rollback-on-host.sh and by an operator.
#
# Why a ledger exists at all
#   The one fact a rollback needs is "which version was last known good", and the
#   one file that looks like it answers that cannot. `APP_IMAGE_TAG` in `.env` is
#   what the host is CONFIGURED for, and a deployment rewrites it — and pulls, and
#   starts a container — BEFORE it can discover that the new version does not come
#   up. So the moment a release fails, `.env` reports the broken version, and a
#   rollback that trusted `.env` would redeploy the thing that just failed. That
#   is not a hypothetical: it is the exact sequence deploy-on-host.sh performs.
#
#   The ledger is the second opinion. It is APPEND-ONLY and records the OUTCOME
#   of each attempt, so "last known stable" is a query over history rather than a
#   reading of mutable configuration.
#
# The file
#   $RELEASES_DIR/ledger.tsv — tab-separated, one row per attempt:
#
#     <utc-timestamp> <version> <environment> <outcome> <note>
#
#   outcome is one of:
#     deployed  the container started and its own healthcheck passed, but nothing
#               outside the host has confirmed it yet
#     success   verified from the agent — /actuator/health answered over the
#               network AND the smoke test passed. A version is STABLE only when
#               it has a `success` row; the agent writes that row, because the
#               claim "the release is serving" is the agent's to make.
#     failed    the attempt did not come up, or did not verify
#
#   `last-stable` is the most recent version with a `success` row, and
#   `previous-stable` is the most recent DIFFERENT version with one. The second
#   definition is not pedantry: a rollback writes another `success` row for the
#   version it restored, so two consecutive success rows for one version are the
#   ordinary result of recovering from a failed release. "The one before it" read
#   as "the second-to-last row" would then name that same version — see the
#   `previous-stable` branch below for what that costs.
#
#   Nothing is ever edited or deleted — a correction is a new row, for the same
#   reason submission_status_history is append-only.
#
# Usage
#   release-ledger.sh record <version> <environment> <outcome> [<note>]
#   release-ledger.sh current            # version of the LAST row, whatever it is
#   release-ledger.sh last-stable        # most recent version with a success row
#   release-ledger.sh previous-stable    # the last success under a different version
#   release-ledger.sh history            # the whole ledger, raw
#   release-ledger.sh path               # the ledger file's location
#
#   `current`, `last-stable` and `previous-stable` print a version, or an empty
#   line when there is none. They exit 0 either way: "no previous version" is a
#   fact a caller must be able to ask about, not a failure to report.
# ===========================================================================

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=deploy/host-common.sh
. "$SCRIPT_DIR/host-common.sh"

ensure_ledger() {
    [ -d "$RELEASES_DIR" ] || mkdir -p "$RELEASES_DIR" || fail "cannot create $RELEASES_DIR"
    [ -f "$LEDGER_FILE" ] || : >"$LEDGER_FILE" || fail "cannot create $LEDGER_FILE"
}

# Every version that has a success row, oldest first. Absent ledger = no output.
#
# Note what this does NOT do: it does not collapse repeats. The same version legitimately
# appears more than once — a rollback restores a version that already had a success row, and
# the agent writes a second one for it after verifying the restore — so the stream is a
# history of successes, not a list of distinct versions. `previous-stable` is what has to
# cope with that, not this.
stable_versions() {
    [ -f "$LEDGER_FILE" ] || return 0
    awk -F'\t' '$4 == "success" { print $2 }' "$LEDGER_FILE"
}

cmd="${1:-}"

case "$cmd" in
    record)
        version="${2:-}"
        environment="${3:-}"
        outcome="${4:-}"
        note="${5:-}"

        require_version "$version"
        [ -n "$environment" ] || fail "record needs an environment."
        case "$outcome" in
            deployed | success | failed) ;;
            *) fail "record needs an outcome of 'deployed', 'success' or 'failed', not '$outcome'." ;;
        esac

        ensure_ledger
        printf '%s\t%s\t%s\t%s\t%s\n' \
            "$(utc_timestamp)" "$version" "$environment" "$outcome" "$note" >>"$LEDGER_FILE"
        ;;

    current)
        if [ -s "$LEDGER_FILE" ]; then
            tail -n 1 "$LEDGER_FILE" | cut -f2
        else
            echo ""
        fi
        ;;

    last-stable)
        stable_versions | tail -n 1
        ;;

    previous-stable)
        # The most recent version that recorded a success AND differs from the last stable
        # one — not simply the second-to-last success row.
        #
        # Why the difference matters. A rollback restores a version that already had a success
        # row, and the agent then writes a second one for it, so consecutive success rows for
        # ONE version are the normal outcome of recovering from a failed release, not an edge
        # case. Taking the second-to-last row would then return that same version, and
        # `previous-stable == last-stable` has two consequences, both wrong:
        #
        #   * rollback-on-host.sh's "there is no earlier stable version to step back to"
        #     refusal keys on an EMPTY previous-stable, so it would not fire;
        #   * the operator who asked to step one release back would instead get
        #     `TARGET == CONFIGURED_TAG`, the "nothing to change" branch, which exits 0 and
        #     prints `rolled-back-to=<version>` — a successful rollback reported for a
        #     rollback that changed nothing, and a `success` row written on the strength of it.
        #
        # Filtering by inequality can only ever narrow the answer: it returns a version that
        # genuinely recorded a success and is not the one already running, or nothing at all
        # — and nothing at all produces rollback-on-host.sh's explicit refusal rather than a
        # wrong target.
        last_stable="$(stable_versions | tail -n 1)"
        [ -n "$last_stable" ] || exit 0
        stable_versions | awk -v last="$last_stable" '$0 != last' | tail -n 1
        ;;

    history)
        if [ -f "$LEDGER_FILE" ]; then
            cat "$LEDGER_FILE"
        fi
        ;;

    path)
        echo "$LEDGER_FILE"
        ;;

    *)
        {
            echo "Usage: release-ledger.sh <command> [arguments]"
            echo "       record <version> <environment> <outcome> [<note>]"
            echo "       current | last-stable | previous-stable | history | path"
        } >&2
        exit 2
        ;;
esac
