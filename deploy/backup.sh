#!/bin/sh
# ===========================================================================
# Backups for the two persistent volumes the deployment owns.
#
# Runs ON the host. Called by pre-release.sh (the automatic, before-every-release
# MySQL dump) and by an operator, through scripts/ops/backup-restore.cmd.
#
# What is here, and the asymmetry between the two halves
#   ADR-004 splits one artefact across two stores: the row goes in MySQL, the
#   bytes go on the uploads volume. A backup that captured only one of them would
#   restore a database of submissions whose files are missing — every download a
#   404 — or a volume of files no row points at. So both are covered.
#
#   They are not covered the same way, and the difference is honest rather than
#   hidden. The MySQL dump is taken automatically before every release, because
#   it is the half a bad release is most likely to damage and the half a restore
#   can rebuild from one command. The uploads archive is an operator action,
#   because a volume tar on a live host costs time against a volume that a
#   release never writes to (releases replace the application CONTAINER; the
#   volume is untouched), so the release path does not pay for it on every
#   release. backup-restore.md §1 records this and says what it costs.
#
# No credential is ever on a command line
#   The MySQL password is read from the CONTAINER's own environment — the `db`
#   service sets MYSQL_ROOT_PASSWORD from the host's `.env`, and the inner `sh -c`
#   expands it inside the container. It therefore never appears in the host's
#   process list, in this script's arguments, or in a log line. `MYSQL_PWD` rather
#   than `-p` for the same reason inside the container.
#
# Usage
#   backup.sh mysql                    # dump the database into BACKUPS_DIR
#   backup.sh uploads                  # archive the uploads volume into BACKUPS_DIR
#   backup.sh list                     # the backups present, newest last
#   backup.sh restore-mysql <file>     # load a dump (see the warning it prints)
#   backup.sh restore-uploads <file>   # unpack an uploads archive
#
# Environment
#   DEPLOY_DIR, COMPOSE_FILE, ENV_FILE, BACKUPS_DIR — see host-common.sh
#   RESTORE_CONFIRM=<value>            # REQUIRED by restore-*; must equal the
#                                      # basename of the file being restored.
#                                      # A restore DESTROYS current data, so it
#                                      # has to be asked for by name.
#
# Exit codes
#   0  the operation succeeded
#   2  the arguments were refused
#   1  anything else — a missing file, a failed dump, a container that is not
#      running. The message names which.
# ===========================================================================

set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# shellcheck source=deploy/host-common.sh
. "$SCRIPT_DIR/host-common.sh"

cd "$DEPLOY_DIR" || fail "cannot enter $DEPLOY_DIR"
command -v docker >/dev/null 2>&1 || fail "docker is not on PATH on this host."

# Filename-safe UTC stamp. The ledger keeps the readable form; a filename cannot
# hold the colons of a full ISO-8601 timestamp on every filesystem.
compact_timestamp() {
    date -u +%Y%m%dT%H%M%SZ
}

ensure_backups_dir() {
    [ -d "$BACKUPS_DIR" ] || mkdir -p "$BACKUPS_DIR" || fail "cannot create $BACKUPS_DIR"
}

mysql_dump_to() {
    docker compose -f "$COMPOSE_FILE" exec -T "$DB_SERVICE" sh -c '
        MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump \
            --user=root \
            --single-transaction \
            --routines \
            --triggers \
            --no-tablespaces \
            --databases "$MYSQL_DATABASE"
    '
}

backup_mysql() {
    ensure_backups_dir

    compose_service_running "$DB_SERVICE" || fail "the '$DB_SERVICE' container is not running, so the database cannot be dumped. Start the stack, or restore from an existing dump — there is no way to read InnoDB's files out of a stopped container."

    version="$(read_env_var "$MANAGED_TAG_VAR")"
    [ -n "$version" ] || version="unknown"

    out="$BACKUPS_DIR/mysql-$(compact_timestamp)-$version.sql.gz"
    tmp="$out.tmp"

    say "[backup] Dumping the database into $out..."
    if ! mysql_dump_to >"$tmp" 2>/dev/null; then
        rm -f "$tmp"
        fail "mysqldump failed. No backup was written; the previous backups are untouched."
    fi

    # A dump that is empty, or that is an error message rather than SQL, must be
    # caught HERE — a zero-byte .gz is a backup that "exists" and restores
    # nothing, which is worse than no backup at all because it is trusted.
    if [ ! -s "$tmp" ] || ! grep -q 'MySQL dump' "$tmp"; then
        rm -f "$tmp"
        fail "the dump is empty or is not a MySQL dump. No backup was written."
    fi

    gzip -c "$tmp" >"$out"
    rm -f "$tmp"
    say "[backup] Wrote $(du -h "$out" | cut -f1) to $out"
    echo "$out"
}

backup_uploads() {
    ensure_backups_dir

    repository="$(read_env_var IMAGE_REPOSITORY)"
    tag="$(read_env_var "$MANAGED_TAG_VAR")"
    if [ -z "$repository" ] || [ -z "$tag" ]; then
        fail "IMAGE_REPOSITORY and $MANAGED_TAG_VAR must be set in $ENV_FILE to name the image the archive is taken from."
    fi

    out="$BACKUPS_DIR/uploads-$(compact_timestamp)-$tag.tar.gz"

    # A throwaway container from the deployment's OWN image, mounting the volume
    # read-only. No image is pulled that is not already on the host, the app
    # container does not have to be running (which matters: the moment an operator
    # wants this archive is often the moment the stack is down), and `:ro` means
    # this command cannot modify the volume it is copying.
    #
    # It depends on `tar` being present in the runtime image. eclipse-temurin:21-jre
    # is Ubuntu-based and provides it; the command that proves it on a given host is
    #   docker run --rm <repository>:<tag> tar --version
    # and it is recorded as an unverified assumption in backup-restore.md §6.
    say "[backup] Archiving the uploads volume into $out..."
    if ! docker run --rm -v "assignment-portal-uploads:/data:ro" "$repository:$tag" \
        tar -czf - -C /data . >"$out" 2>/dev/null; then
        rm -f "$out"
        fail "archiving the uploads volume failed. No archive was written."
    fi

    if [ ! -s "$out" ]; then
        rm -f "$out"
        fail "the uploads archive is empty. No archive was written."
    fi

    say "[backup] Wrote $(du -h "$out" | cut -f1) to $out"
    echo "$out"
}

# A restore replaces data. It is the one operation in this tooling that can
# destroy something, so it is gated on the caller naming the file it is about to
# restore — which is a decision, not a convenience.
require_restore_confirmation() {
    file="$1"
    name=$(basename -- "$file")
    if [ "${RESTORE_CONFIRM:-}" != "$name" ]; then
        say "[backup] This would REPLACE data from $name."
        say "[backup] To proceed, re-run with RESTORE_CONFIRM=$name"
        FAIL_CODE=2 fail "restore not confirmed."
    fi
    [ -f "$file" ] || fail "$file does not exist."
}

restore_mysql() {
    file="$1"
    require_restore_confirmation "$file"

    compose_service_running "$DB_SERVICE" || fail "the '$DB_SERVICE' container is not running, so the dump cannot be loaded."

    say "[backup] Restoring $file into the database..."
    say "[backup] The dump carries DROP TABLE for each table it contains, so this REPLACES the current contents of those tables."

    # `--databases` in the dump means it carries its own CREATE DATABASE and USE,
    # so no target database is named here: a dump taken from one host restores
    # into a freshly created database on another without an operator renaming it.
    if ! gunzip -c "$file" | docker compose -f "$COMPOSE_FILE" exec -T "$DB_SERVICE" sh -c '
        MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --user=root
    '; then
        fail "the restore failed part-way. The database may now hold a mixture of the dump and its previous contents — verify before serving traffic."
    fi

    say "[backup] Restore complete."
}

restore_uploads() {
    file="$1"
    require_restore_confirmation "$file"

    repository="$(read_env_var IMAGE_REPOSITORY)"
    tag="$(read_env_var "$MANAGED_TAG_VAR")"
    [ -n "$repository" ] && [ -n "$tag" ] || fail "IMAGE_REPOSITORY and $MANAGED_TAG_VAR must be set in $ENV_FILE."

    say "[backup] Restoring $file into the uploads volume..."
    say "[backup] This unpacks over the existing contents; a file present in the volume and absent from the archive is NOT removed."
    if ! gzip -c -d "$file" | docker run --rm -i -v "assignment-portal-uploads:/data" "$repository:$tag" \
        tar -xzf - -C /data; then
        fail "unpacking the uploads archive failed. The volume may now hold a mixture of the archive and its previous contents."
    fi
    say "[backup] Restore complete."
}

list_backups() {
    if [ ! -d "$BACKUPS_DIR" ]; then
        say "[backup] $BACKUPS_DIR does not exist; this host has no backups."
        return 0
    fi
    # ls -lt: newest first, which is what an operator wants when choosing one.
    ls -lt "$BACKUPS_DIR" 2>/dev/null | grep -E '\.(sql|tar)\.gz$' || say "[backup] $BACKUPS_DIR holds no backups."
}

case "${1:-}" in
    mysql) backup_mysql ;;
    uploads) backup_uploads ;;
    list) list_backups ;;
    restore-mysql)
        [ -n "${2:-}" ] || FAIL_CODE=2 fail "restore-mysql needs the dump file to restore."
        restore_mysql "$2"
        ;;
    restore-uploads)
        [ -n "${2:-}" ] || FAIL_CODE=2 fail "restore-uploads needs the archive to restore."
        restore_uploads "$2"
        ;;
    *)
        {
            echo "Usage: backup.sh <command> [arguments]"
            echo "       mysql | uploads | list"
            echo "       restore-mysql <file> | restore-uploads <file>"
            echo ""
            echo "Restores require RESTORE_CONFIRM set to the file's basename."
        } >&2
        exit 2
        ;;
esac
