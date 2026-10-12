# Backup and restore

> **Status: written, not run.** No backup has been taken from a real database and no restore has
> been performed — not on a host, and not against a stub. `deploy/tests/release-rollback-demo.sh`
> was written to drive these scripts against stub `mysql`/`mysqldump` binaries and has never been
> executed. §6 states that boundary claim by claim.

## 1. What is backed up, and the asymmetry between the two halves

A submission is one artefact in **two stores**, and this is the fact the whole page follows from:

| Half | Store | What it is |
| --- | --- | --- |
| The **row** | MySQL, in the `assignment-portal-mysql-data` volume | Metadata: owner, title, status, checksum, timestamps, the decision and its reason, the status history |
| The **bytes** | The `assignment-portal-uploads` volume | The PDF itself, written outside any servable path |

A backup that captured one and not the other would restore a database of submissions whose files are
404s, or a volume of files no row points at. **Both are covered.**

They are not covered the *same* way, and the difference is a judgement rather than an oversight:

| Half | When | Command | Why |
| --- | --- | --- | --- |
| MySQL | **Automatically, before every release**, by the pre-release gate | `sh backup.sh mysql` | It is the half a bad release is most likely to damage, the half a migration can change irreversibly, and the half a restore can rebuild from one command |
| Uploads | **On demand**, by an operator | `sh backup.sh uploads` | A release replaces the application **container**; the volume is untouched. Archiving a volume costs time on a live host, and the release path does not pay for it on every release |

That asymmetry is real and its cost is stated rather than hidden: **a release does not, by itself,
give you a current archive of the uploads volume.** If the volume is lost, the most recent uploads
archive is the last time somebody ran the command. §6 records this as a gap.

Rows and bytes also differ in what a restore means. Rows are versioned with the schema — a dump
carries `DROP TABLE` and is restored into the same shape. Bytes are not: a file on the volume either
exists or it does not, and nothing about it depends on the application's version.

---

## 2. Where backups live, and what they are called

`DEPLOY_PATH` (default `/opt/assignment-portal`), in the `backups/` directory:

| Kind | Filename | Notes |
| --- | --- | --- |
| MySQL | `mysql-<YYYYmmddTHHMMSSZ>-<version>.sql.gz` | The stamp is UTC, so backups taken across hosts sort into one order. `<version>` is what `.env` named when the dump was taken — the version that was **running**, which is the thing a restore is usually paired with |
| Uploads | `uploads-<YYYYmmddTHHMMSSZ>-<version>.tar.gz` | Same stamp. `<version>` names the image the archiving container came from |

Two deliberate properties:

- **The directory is on the host's own filesystem, outside Docker's storage root.** A
  `docker system prune -a --volumes` must not be able to take the release history or a database dump
  with it.
- **The directory is `0750`**, created by the Ansible `application` role, or by
  `scripts/ops/copy-host-scripts.cmd` on a host reached only by the pipeline. A dump is a copy of
  every submission record.

Because a filename cannot carry the colons of a full ISO-8601 timestamp on every filesystem, the
filename uses the compact UTC form while the ledger keeps the readable one.

---

## 3. The automatic backup

`deploy/pre-release.sh` runs as the **first** step of every deployment, before a line of
configuration is rewritten or a container is stopped.

- The `db` container is running → `sh backup.sh mysql`, and the path is recorded as `BACKUP_FILE` in
  `releases/<version>.env`.
- The dump fails, or is empty, or does not look like a MySQL dump → **the release refuses.** A
  release that cannot be backed up must not get far enough to replace a container that is serving.
- The `db` container is **not** running and the host has released before → the gate **refuses**,
  unless `ALLOW_RELEASE_WITHOUT_BACKUP=1` is set. There is data to lose, so releasing over an
  unreachable database is a decision rather than a default.
- The `db` container is not running and this is the **first** release → there is genuinely nothing to
  back up, `BACKUP_FILE=none`, and the gate proceeds.

The dump is taken with `--single-transaction`, so it is consistent without locking the tables the
application is writing to. A release backup is therefore taken against a **live** database and does
not stop the world.

### Why the empty-dump check exists

A zero-byte `.gz` is a backup that "exists" and restores nothing. It is worse than no backup at all,
because it is trusted. So `backup.sh` writes to a temporary file first, refuses a dump that is empty
or that does not contain the `MySQL dump` marker, and only then compresses and renames it into place.
A failed dump leaves the previous backups untouched and writes nothing new.

---

## 4. Taking one on demand

From the Jenkins agent, or from anywhere with an `ssh` route to the host:

```cmd
rem The database, now, on the host:
scripts\ops\backup-restore.cmd backup-mysql

rem The uploads volume, now, on the host:
scripts\ops\backup-restore.cmd backup-uploads

rem What is there, newest first:
scripts\ops\backup-restore.cmd list

rem Bring one back to the agent's target\ directory — a second copy, on other hardware:
scripts\ops\backup-restore.cmd fetch mysql-20261010T041500Z-1.0.0.sql.gz
```

On the host itself:

```bash
cd /opt/assignment-portal
DEPLOY_DIR=/opt/assignment-portal sh backup.sh mysql
DEPLOY_DIR=/opt/assignment-portal sh backup.sh uploads
DEPLOY_DIR=/opt/assignment-portal sh backup.sh list
```

### Why the uploads archive uses the deployment's own image

`backup.sh uploads` runs a throwaway container from **the image the host is already running**
(`IMAGE_REPOSITORY:$APP_IMAGE_TAG`), mounting the volume `:ro`:

```
docker run --rm -v assignment-portal-uploads:/data:ro <repository>:<tag> tar -czf - -C /data .
```

Three consequences, all of them the reason for the choice:

- No image is pulled that is not already on the host.
- The application container does **not** have to be running — which matters, because the moment an
  operator wants this archive is often the moment the stack is down.
- `:ro` means the command cannot modify the volume it is copying.

It depends on `tar` being present in the runtime image. `eclipse-temurin:21-jre` is Ubuntu-based and
provides it. The command that proves it on a given host is
`docker run --rm <repository>:<tag> tar --version`, and §6 records that it has not been run.

---

## 5. Restoring

**A restore replaces data.** It is the one operation in this tooling that can destroy something, so
it is gated on the caller naming the file it is about to restore.

### The gate

Both the agent command and the host script require `RESTORE_CONFIRM` to equal the **basename** of
the file being restored:

```cmd
set RESTORE_CONFIRM=mysql-20261010T041500Z-1.0.0.sql.gz
scripts\ops\backup-restore.cmd restore-mysql mysql-20261010T041500Z-1.0.0.sql.gz
```

The check is performed **on the host**, in `deploy/backup.sh`, not only on the agent. The host is the
thing that overwrites data, so the host is the thing that must refuse; a gate that lived only on the
agent would be a gate a hand-run `ssh` command walks straight past.

### Restoring the database

```bash
# On the host:
DEPLOY_DIR=/opt/assignment-portal RESTORE_CONFIRM=mysql-...sql.gz \
    sh backup.sh restore-mysql backups/mysql-...sql.gz
```

What it does, and what it costs:

- The dump was taken with `--databases`, so it carries its own `CREATE DATABASE` and `USE`. No target
  database is named at restore time — which is what lets a dump taken on one host restore into a
  freshly created database on another without an operator renaming anything.
- **The dump carries `DROP TABLE` for each table it contains, so it REPLACES the current contents of
  those tables.** Everything written since the dump was taken is gone. This is not a merge.
- Tables *not* in the dump are left alone.
- `db` must be **running**. There is no way to read InnoDB's files out of a stopped container.
- If it fails part-way, the database may hold a mixture of the dump and its previous contents. The
  script says so on the way out rather than reporting a clean failure.

### Restoring the uploads volume

```bash
# On the host:
DEPLOY_DIR=/opt/assignment-portal RESTORE_CONFIRM=uploads-...tar.gz \
    sh backup.sh restore-uploads backups/uploads-...tar.gz
```

- Files are **unpacked over** the existing contents. A file present in the volume and absent from
  the archive is **not removed** — this is a merge, not a replacement, and it is the opposite of the
  database's behaviour. The asymmetry is a real property of the two stores and is stated here
  because getting it wrong in either direction is silent.
- It runs a container from the deployment's own image, as the backup does.

### After a restore

**The running application is not restarted, and you should restart it.** It holds database
connections and a connection pool that predate the restored rows. The `backup-restore.cmd` output
says this on completion, and it is the step most easily forgotten:

```bash
cd /opt/assignment-portal
docker compose -f compose.prod.yaml restart app
```

Then verify the deployment the way a release does — `/actuator/health` and the smoke test
([`release-runbook.md`](release-runbook.md) §6). A restore is not finished when the command returns;
it is finished when the application is serving the restored data.

---

## 6. Verification status

| Claim | Status |
| --- | --- |
| `backup.sh mysql` writes a gzipped dump and refuses an empty or non-dump file | **Written, not run.** The demo would run the real `backup.sh` against a stubbed `mysqldump`, exercising the ordering, the filename, the emptiness check and the `MySQL dump` marker check — but the demo has not run, and the dump would be a fixture in any case |
| `pre-release.sh` refuses a release when the backup cannot be taken | **Written, not run.** The refusal path is code |
| `pre-release.sh` proceeds without a backup on a first release | **Written, not run** |
| The required-volume check refuses when the host has released before | **Written, not run** |
| `backup.sh uploads` archives the volume with a throwaway container | **Written, not run.** No real Docker daemon has been involved |
| `tar` is present in `eclipse-temurin:21-jre` | **Not verified.** The command that would check it is recorded above and has not been run |
| `restore-mysql` loads a dump and replaces the tables it names | **Not run.** No real MySQL has been involved |
| `restore-uploads` unpacks over the volume without removing absent files | **Not run** |
| The `RESTORE_CONFIRM` gate refuses an unconfirmed restore | **Written, not run.** It is a two-line comparison in `backup.sh`, and nothing has executed it |
| A restore has ever been rehearsed end to end on a host | **No** |
| `./mvnw clean verify` is green | **No.** `CLAUDE.md` §13's precondition for this work is still unmet |

**The honest summary: nothing on this page has been executed — not against MySQL, not against a
stubbed MySQL, not at all.** `deploy/tests/release-rollback-demo.sh` was written to drive the backup
and restore scripts under stubs, and it has never run: the environment this work was done in refused
every attempt to execute it. A backup that has never been restored from is a hypothesis, and a backup
script that has never been executed is a smaller kind of hypothesis still — one step further from
evidence than the page previously claimed.

The command that would change that, on a host running the stack:

```bash
# Take one, restore it into a scratch database, and diff. On a host you may break.
DEPLOY_DIR=/opt/assignment-portal sh backup.sh mysql
```

---

## 7. Known gaps

- **No retention policy.** `backups/` grows by one dump per release and nothing rotates it. A host
  releasing daily accumulates dumps until its disk fills; the *next* release then fails its
  pre-release gate — which is the gate working, but as a surprise. Deciding retention is a decision
  for whoever owns the host, and a script that silently deleted the oldest dump would eventually
  delete the one an operator needs. Nothing here does it.
- **No off-host copy, by default.** A backup that only exists on the machine it protects is not a
  backup of that machine's failure modes — a lost host takes its dumps with it.
  `scripts/ops/backup-restore.cmd fetch` copies one to the Jenkins agent, which is a **second** copy
  on different hardware, but it is on demand and nobody has made it routine. The agent is not
  off-site either.
- **No scheduled backups.** Every dump is either the pre-release one or an operator action. Nothing
  takes one on a timer, so a host that is not being released is a host that is not being backed up.
- **The uploads volume is archived on demand only** (§1). The release path does not archive it.
- **No restore is ever rehearsed.** §6. This is the largest gap on the page: the tooling's failure
  modes and the dump's restorability are both unproven.
- **No encryption at rest for the dumps.** They sit on the host's disk in a `0750` directory. The
  directory mode is a control; it is not encryption, and anyone with root on the host has every
  submission record.
- **No verification that a dump is complete.** The gate proves a dump is non-empty and looks like a
  dump. It does not prove the dump's table set is complete, and MySQL has no checksum in the default
  output.
