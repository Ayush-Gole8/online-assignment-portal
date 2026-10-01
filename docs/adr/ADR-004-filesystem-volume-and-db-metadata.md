# ADR-004 — Filesystem volume plus database metadata for uploads

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Repository maintainer
- **Related:** [ADR-003](ADR-003-mysql-system-of-record.md) · [ADR-002](ADR-002-spring-boot-embedded-tomcat.md) · [Data model §6](../architecture/data-model.md) · [SECURITY.md](../../SECURITY.md)

## Context

Students upload PDF files of up to 10 MB. Each uploaded file must be **durable**, **served
back** to its owner and to reviewers, and **accounted for** — the portal records a SHA-256
checksum, so the bytes themselves are subject to an integrity requirement, not just the
metadata.

Three storage models were realistic:

1. **Bytes in the database** as a `BLOB`.
2. **Bytes on the container's own filesystem.**
3. **Bytes on a persistent volume, with metadata in the database.**

Model (2) is a trap. The container filesystem is **ephemeral**: a redeploy — which this
pipeline performs on every merge — replaces the container and silently destroys every
uploaded file, while the database rows that reference them survive. The result is a database
full of submissions whose files are gone. This is exactly the failure the deployment pipeline
would cause on its very first run, so it must be designed out rather than discovered.

Model (1) has real merits: files and metadata share one transaction and one backup. It also
puts multi-megabyte binaries into the database's buffer pool and replication stream, bloats
every backup, and is served through the JVM heap. MySQL's `max_allowed_packet` also becomes a
correctness-relevant setting.

## Decision

Store uploaded file **bytes on a persistent filesystem volume**, and store **metadata in
MySQL**. Specifically:

- The storage root is supplied by an **environment variable** (`STORAGE_ROOT`) and is mounted
  as a **named Docker volume**. The application never writes uploads into its own image or
  into the container layer.
- Every upload is written under a **server-generated storage identifier**, stored in
  `submissions.storage_identifier` (unique). The **original filename is never used as a
  path** — it is retained purely as display metadata.
- The **SHA-256 checksum** is computed at upload time and stored in
  `submissions.checksum_sha256`.
- The database row and the file are linked by exactly one value — the storage identifier — and
  the checksum is the tie-breaker if they ever disagree.
- The volume is **not** removed by an ordinary `docker compose down`; only an explicit
  `down -v` destroys it.
- Files are served back as **attachments** with a fixed `application/pdf` content type, never
  rendered inline.

**Write ordering:** the file is written to the volume **before** the database row is
committed. If the commit then fails, the result is an orphaned file — wasteful but harmless.
The reverse order would produce a row with no file, which breaks downloads and cannot be
detected by the user. Where two failures are possible, prefer the recoverable one.

## Consequences

**Positive**

- **Uploads survive redeployment.** This is the property that makes the pipeline safe to run.
- **The database stays small and fast.** No multi-megabyte rows in the buffer pool, backups,
  or replication.
- **Files are servable efficiently** — streamed from disk rather than materialised on the
  heap.
- **Path traversal is structurally impossible**, because no client-supplied string ever
  reaches a path. This is a design property, not a validation rule that a future refactor
  could remove.
- **Integrity is verifiable.** The stored checksum lets the application detect corruption or
  substitution at rest.
- **Storage is swappable.** Because the application talks to a `StorageService` and knows only
  an opaque identifier, moving to object storage later changes one package, not the domain.

**Negative / accepted costs**

- **Two stores must be backed up and kept consistent.** A backup of the database alone does not
  restore the system. This is a real operational burden and is recorded here deliberately.
  Mitigation: the checksum makes a mismatch detectable, and the identifier scheme makes an
  orphaned file identifiable.
- **No transactional atomicity between file and row.** There is a window where a file exists
  without a row (harmless) or, if the volume is lost, a row without a file (harmful). The write
  ordering above narrows this to the recoverable case only.
- **A single host's volume is a single point of failure.** The MVP runs one instance on one
  host. Multi-host storage would require object storage or a shared filesystem — out of scope
  (`mvp.md` §3 #13).
- **Cleanup of orphans requires a deliberate job.** Not built in the MVP; the volume grows with
  orphaned files until a reconciliation script is added.

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| **`BLOB` in MySQL** | One transaction, one backup, one consistency story. Rejected because it puts large binaries into the buffer pool and every backup, inflates the replication stream, ties file serving to JVM heap, and makes `max_allowed_packet` a correctness-critical setting. It also removes the "swap the storage backend" seam. |
| **Container-local filesystem** | Fails on the first redeploy, and this pipeline redeploys on every merge. Not viable. |
| **Object storage (S3-compatible)** | The architecturally ideal answer for scale and durability. Rejected as an external dependency and credential to manage in every environment, for a single-host MVP deployment. The `StorageService` seam keeps this cheap to adopt later. |
| **A separate file server (NFS/SFTP)** | Another host to provision and secure, for one application on one host. Ansible would be provisioning infrastructure to solve a problem a Docker volume already solves. |
| **Store the original filename as the path** | A path-traversal vulnerability and a collision hazard (`../../etc/passwd`, two students both uploading `assignment.pdf`). Rejected absolutely — see [`CLAUDE.md` §7](../../CLAUDE.md). |

## Follow-up

- Backup and restore of the volume must be documented in `docs/operations/` before the first
  production deployment. A backup that has never been restored is a hypothesis, not a backup.
- Upload handling must be tested with hostile filenames (traversal sequences, unicode
  confusables) and with content that is not a PDF despite a `.pdf` extension — see
  [testing strategy](../testing/strategy.md).
