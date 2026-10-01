# Data Model

**Status:** Partially implemented — Milestone 3 (`users` and `submissions` migrated and mapped;
`submission_status_history` and `audit_events` still planned)
**Related:** [System overview](system-overview.md) · [Security architecture](security.md) ·
[Application structure](application-structure.md) ·
[ADR-003](../adr/ADR-003-mysql-system-of-record.md) ·
[ADR-004](../adr/ADR-004-filesystem-volume-and-db-metadata.md) · [MVP requirements](../requirements/mvp.md)

This is the persistence model. Column names are shown as they appear in MySQL. **§2 and §3 are
implemented** — `V1__baseline.sql` and `V2__submissions.sql` create them, and `auth/User.java` and
`submission/Submission.java` map them. **§4 and §5 are still planned**: those tables do not exist
yet, and each is the contract the migration implementing it must satisfy.

> **Amended 2026-10-01 (identity).** §2 originally specified a `BIGINT` surrogate key with
> `username` and `display_name` columns. It now specifies a `UUID` identifier and an `email` login
> identifier, to match the Milestone 2 brief. The amendment was safe because `V1__baseline.sql`
> had never been applied in any environment — see §7.
>
> **Amended 2026-10-01 (submissions).** §3 originally specified an `assignment_code` referencing a
> seeded assignment catalogue, with `submitted_at` / `decided_at` / `decided_by` names. The
> implemented table uses free-text `title` and `course_code`, and `created_at` / `updated_at` /
> `reviewed_at` / `reviewer_id`. The differences and their reasons are recorded in §3 under
> "Deviations from the original specification". This table was never applied before it was written,
> so the same reasoning as §2 applies.

---

## 1. Overview

Four entities in the MVP. The design is deliberately small: a user owns submissions, and every
status change to a submission appends to a history table.

Solid relationships are implemented; dashed ones are planned.

```mermaid
erDiagram
    USERS ||--o{ SUBMISSIONS : "submits"
    USERS ||--o{ SUBMISSION_STATUS_HISTORY : "acts as actor"
    SUBMISSIONS ||--o{ SUBMISSION_STATUS_HISTORY : "has transitions"
    USERS ||--o{ AUDIT_EVENTS : "acts as actor"

    USERS {
        varchar(36) id PK
        varchar(254) email UK
        varchar(100) password_hash
        varchar(16) role
        boolean enabled
        datetime(6) created_at
        datetime(6) updated_at
    }

    SUBMISSIONS {
        varchar(36) id PK
        varchar(36) student_id FK
        varchar(200) title
        varchar(20) course_code
        varchar(255) original_file_name
        varchar(36) stored_file_name UK
        varchar(100) content_type
        bigint file_size_bytes
        char(64) checksum_sha256
        varchar(16) status
        datetime(6) created_at
        datetime(6) updated_at
        datetime(6) reviewed_at
        varchar(36) reviewer_id FK
        varchar(2000) rejection_reason
        bigint version
    }

    SUBMISSION_STATUS_HISTORY {
        bigint id PK
        varchar(36) submission_id FK
        varchar(16) previous_status
        varchar(16) new_status
        varchar(36) actor_id FK
        varchar(2000) comment
        datetime(6) occurred_at
    }

    AUDIT_EVENTS {
        bigint id PK
        varchar(36) actor_id FK
        varchar(64) event_type
        varchar(64) target_type
        varchar(64) target_id
        varchar(1000) detail
        varchar(45) ip_address
        datetime(6) occurred_at
    }
```

Every foreign key that points at `users(id)` must be `VARCHAR(36)` to match, because MySQL
requires the referenced and referencing columns to have the same type. `USERS` and `SUBMISSIONS`
exist; the remaining two are the corrected contract for the migrations that will create them.

`AUDIT_EVENTS` is required by FR-24 but is not one of the three named entities in the milestone
brief; it is listed here so the migration that introduces the audit trail can create it in the same
pass. **It does not exist yet** — see [security.md §12](security.md#12-what-this-milestone-does-not-do).

## 2. `users`

Stores identity and role. Passwords are stored only as a BCrypt hash.

**Implemented** by `V1__baseline.sql`, mapped by `auth/User.java`.

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | `VARCHAR(36)` | PK | Application-assigned UUID. Not auto-increment |
| `email` | `VARCHAR(254)` | NOT NULL, **UNIQUE**, case-insensitive | Login identifier |
| `password_hash` | `VARCHAR(100)` | NOT NULL | BCrypt output only. Never plaintext, never reversible |
| `role` | `VARCHAR(16)` | NOT NULL | `STUDENT` \| `REVIEWER` \| `ADMIN` |
| `enabled` | `BOOLEAN` | NOT NULL, default `TRUE` | Disabled users cannot authenticate |
| `created_at` | `DATETIME(6)` | NOT NULL | UTC |
| `updated_at` | `DATETIME(6)` | NOT NULL | UTC |

**Indexes:** unique index on `email` (`uq_users_email`).

**Decisions:**

- The identifier is a `UUID` rendered as `VARCHAR(36)`, not a `BIGINT` and not `BINARY(16)`. It
  appears in URLs, in audit rows, and in log lines; a sequential integer there announces how many
  users exist and invites enumeration. The 20 bytes `BINARY(16)` would save are worth less than
  being able to read an identifier in a `SELECT` or a pipeline log without a decoding step. The
  mapping is pinned by `common/UuidToVarcharConverter`, so the column type stays the migration's
  decision rather than the ORM's — Hibernate's default UUID representation for MySQL has changed
  between major versions, and `ddl-auto: validate` turns that kind of surprise into a startup
  failure.
- The login identifier is an **email address**, not a username. It is what a student already has
  and does not have to be issued; a separate username would be one more thing to remember and one
  more thing to reconcile with the address the portal already needs.
- 254 is the maximum length of an address in the RFC 5321 path, so the column can never silently
  truncate a valid address.
- `role` is a string column, not a numeric ordinal. A readable value survives a schema dump and
  cannot silently change meaning if the enum is reordered. It is validated on write by the `Role`
  enum and again by `ck_users_role` in the database.
- One role per user in the MVP. A future many-role model would add a `user_roles` join table; that
  is out of scope (`mvp.md` §3).
- Email comparison is case-insensitive, enforced by a `utf8mb4_0900_ai_ci` collation on the column
  rather than by lower-casing in application code, so the guarantee holds for any writer. Two people
  cannot register `A@x.com` and `a@x.com` as separate accounts.
- `updated_at` exists here and is written on every update. It is a change-tracking field, not an
  audit log — the audit trail is §5, and the sign-in events that belong in it are still to come.

## 3. `submissions`

One row per uploaded artefact. Contains **metadata only** — the file bytes live on the volume
([ADR-004](../adr/ADR-004-filesystem-volume-and-db-metadata.md)).

**Implemented** by `V2__submissions.sql`, mapped by `submission/Submission.java`.

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | `VARCHAR(36)` | PK | Application-assigned UUID. Appears in the detail page URL |
| `student_id` | `VARCHAR(36)` | NOT NULL, FK → `users(id)`, `ON DELETE RESTRICT` | Owner. A bare identifier, not an entity association |
| `title` | `VARCHAR(200)` | NOT NULL | The student's own title for the work |
| `course_code` | `VARCHAR(20)` | NOT NULL | Free text as the student entered it |
| `original_file_name` | `VARCHAR(255)` | NOT NULL | **Display metadata only.** Never used as a path |
| `stored_file_name` | `VARCHAR(36)` | NOT NULL, **UNIQUE** | Server-generated bare UUID. The only way to locate the file |
| `content_type` | `VARCHAR(100)` | NOT NULL | Recorded as **validated**, not as claimed — `application/pdf` in the MVP |
| `file_size_bytes` | `BIGINT` | NOT NULL | Validated ≤ 10 485 760 |
| `checksum_sha256` | `CHAR(64)` | NOT NULL | Lowercase hex digest of the stored bytes |
| `status` | `VARCHAR(16)` | NOT NULL | `SUBMITTED` \| `APPROVED` \| `REJECTED` |
| `created_at` | `DATETIME(6)` | NOT NULL | UTC. Set on insert |
| `updated_at` | `DATETIME(6)` | NOT NULL | UTC. Bumped by the optimistic lock and by a review decision |
| `reviewed_at` | `DATETIME(6)` | NULL | Set exactly once, with the decision. **Unused until the review feature** |
| `reviewer_id` | `VARCHAR(36)` | NULL, FK → `users(id)`, `ON DELETE RESTRICT` | The reviewer who decided. **Unused until the review feature** |
| `rejection_reason` | `VARCHAR(2000)` | NULL | Non-null only when the status is `REJECTED`. **Unused until the review feature** |
| `version` | `BIGINT` | NOT NULL, default `0` | JPA optimistic lock |

**Indexes:**

- `uq_submissions_stored_file_name` — unique, because two rows pointing at one file would mean one
  artefact overwriting another.
- `idx_submissions_student_id_created_at` on `(student_id, created_at)` — serves the student's own
  list, answering both the filter and the sort (FR-12).
- `idx_submissions_status_created_at` on `(status, created_at)` — serves the reviewer queue
  (FR-16). Created with the table rather than added later, because adding it later means an
  `ALTER` on a table that by then holds data. This is a deliberate exception to "no schema ahead of
  the code", and `V2__submissions.sql` records it as one.

**Constraints and invariants:**

| Invariant | Constraint | Also enforced by |
| --- | --- | --- |
| `status` is one of the three known values | `ck_submissions_status` — `CHECK (status IN ('SUBMITTED','APPROVED','REJECTED'))` | The `SubmissionStatus` enum, and the entity, which has no method that sets any other value |
| `0 < file_size_bytes ≤ 10 MB` | `ck_submissions_file_size` | `FileValidationService`, `spring.servlet.multipart.max-file-size`, and the servlet container |
| A row is either undecided with no reviewer and no time, or decided with both | `ck_submissions_decision` | Nothing yet — the review feature owns the transition |
| Two rows never share a stored file name | `uq_submissions_stored_file_name` | `StorageIdentifier.generate()`, which returns a fresh UUID per upload |
| A submission always has a real owner | `fk_submissions_student_id` | `SubmissionService.submit`, which takes the owner from the authenticated principal |

The application and the database both enforce these. The database is the backstop that survives a
bug in the service; the service is what produces a friendly error. `ApplicationContextIT` asserts
each constraint by inserting a row the service would never produce — a `CHECK` that is never
exercised is one typo away from being a comment.

**Decisions:**

- `student_id` is a **bare identifier**, not a `@ManyToOne` association. The submission feature has
  no business loading an entity another feature owns, and the ownership check is a
  `WHERE student_id = ?` clause — for which an identifier is exactly what is needed. An association
  would also make it possible to traverse from a submission to a user's password hash, which nothing
  here should be able to do.
- `stored_file_name` holds a bare UUID with no extension. The extension would claim a format, and
  the format is already recorded properly in `content_type`; a file named `.pdf` that is not one is
  the kind of thing that misleads whoever inspects the volume at three in the morning.
- The uniqueness of `stored_file_name` is what makes duplicate file names safe. Two students may
  hand in `essay.pdf`; the names the clients sent are identical, and the names on the volume are
  not.
- `original_file_name` is stored because a reviewer needs to see what the student called the file.
  It is normalised before it gets here — directory components and control characters removed, and
  truncated to the column width from the front so the extension survives — by
  `FileValidationService.safeDisplayName`. It is **never** interpolated into a filesystem path.
- `checksum_sha256` is `CHAR(64)`, not `VARCHAR`, because a SHA-256 hex digest has exactly one
  length. It supports integrity verification and future duplicate detection.
- `content_type` is stored even though it is always `application/pdf` in the MVP, so that lifting
  the single-format restriction is a validation change rather than a migration.
- `reviewed_at` / `reviewer_id` are denormalised from the history table, to come, because the
  reviewer's list needs them on every row and joining an append-only log to render a queue is
  wasteful.
- The three decision columns are created now and written by nothing. The entity that describes a
  submission is being designed now, and splitting one table's description across two migrations
  would be the more confusing choice.

**Deviations from the original specification:**

| Was specified | Is implemented | Why |
| --- | --- | --- |
| `assignment_code` against a seeded catalogue | Free-text `title` and `course_code` | The catalogue is out of scope (`mvp.md` §3 #2), and without an `assignments` table an `assignment_code` would be a foreign key to nothing. Free text is what the student actually knows |
| `submitted_at` | `created_at` | The row is created when the work is submitted, and the two would be the same instant. One name for one fact |
| `decided_at` / `decided_by` | `reviewed_at` / `reviewer_id` | The status vocabulary already says `APPROVED`/`REJECTED`; naming the column after the act that produces the decision reads better beside `reviewed_at` than `decided_by` beside `REJECTED` |
| No `updated_at` | `updated_at` present and bumped | The optimistic lock changes the row on every update, and a review decision changes it deliberately. A row whose version moved but whose timestamp did not would be the stranger arrangement |
| `submission_id` as `BIGINT` in the history table | `VARCHAR(36)` in §4 below | It is a foreign key to `submissions(id)`, and MySQL requires matching types |

## 4. `submission_status_history`

**Planned — not created by any migration yet.** Nothing writes it until the review feature exists,
and it is the reviewer's decision that makes an append-only log meaningful. Creating it now would be
schema written ahead of the code that justifies it, which is the reasoning `V1` followed when it
stopped at `users`.

An **append-only** log of every status transition. This table is the audit of record for the
workflow (FR-21).

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | `BIGINT` | PK, auto-increment | Surrogate key |
| `submission_id` | `VARCHAR(36)` | NOT NULL, FK → `submissions(id)` | Restricted delete |
| `previous_status` | `VARCHAR(16)` | NULL | NULL for the initial creation entry |
| `new_status` | `VARCHAR(16)` | NOT NULL | The status after the transition |
| `actor_id` | `VARCHAR(36)` | NOT NULL, FK → `users(id)` | Who caused the transition |
| `comment` | `VARCHAR(2000)` | NULL | Required by the service when the transition is a decision |
| `occurred_at` | `DATETIME(6)` | NOT NULL | UTC |

**Indexes:** index on `(submission_id, occurred_at)`.

**Decisions:**

- Rows are never updated or deleted. If the application ever needs a correction, it appends a
  compensating entry — that is what makes the table trustworthy.
- The creation row has `previous_status = NULL` and `new_status = 'SUBMITTED'`, so a submission's
  full lifecycle is readable from this table alone.
- `comment` is `NULL`-able at the database level but **required by the service** for any transition
  out of `SUBMITTED` (FR-19). The database permits a null so the creation row can exist; the rule
  lives where it is meaningful.
- The creation row is **not** written by the current milestone, even though `submissions` rows are.
  A history table with exactly one entry per submission and no transitions is a table whose shape
  has not been tested by the thing that will use it.

## 5. `audit_events`

**Planned — not created by any migration yet.** Required by FR-24, and the one Milestone 2 entry
criterion that was not met; see [security.md §12](security.md#12-what-this-milestone-does-not-do).

Security-relevant events that are not workflow transitions: sign-in success, sign-in failure, user
created, role changed.

| Column | Type | Constraints | Notes |
| --- | --- | --- | --- |
| `id` | `BIGINT` | PK, auto-increment | |
| `actor_id` | `VARCHAR(36)` | NULL, FK → `users(id)` | NULL when the actor is unknown (e.g. failed login) |
| `event_type` | `VARCHAR(64)` | NOT NULL | e.g. `LOGIN_SUCCESS`, `LOGIN_FAILURE`, `USER_CREATED` |
| `target_type` | `VARCHAR(64)` | NULL | Entity kind acted upon |
| `target_id` | `VARCHAR(64)` | NULL | Entity identifier acted upon |
| `detail` | `VARCHAR(1000)` | NULL | Human-readable context. **Never a secret** |
| `ip_address` | `VARCHAR(45)` | NULL | Fits IPv6 |
| `occurred_at` | `DATETIME(6)` | NOT NULL | UTC |

**Indexes:** index on `(event_type, occurred_at)`; index on `(actor_id, occurred_at)`.

`actor_id` is nullable precisely so a failed authentication attempt can be recorded. A failed
sign-in is exactly the event you most want logged, and at that moment there is no authenticated
principal.

## 6. Storage model

Files are **not** in the database. The contract:

```
submissions.stored_file_name  →  a file directly inside the configured storage root
```

- The name is generated **server-side** by `StorageIdentifier` — a bare lowercase UUID, never
  derived from `original_file_name` (`SECURITY.md`, and [`CLAUDE.md` §7](../../CLAUDE.md)).
  Path traversal is made *unrepresentable* rather than guarded against: the record type rejects
  anything that is not a canonical UUID, so `StorageService` never has to remember to check.
- The root comes from `UPLOAD_STORAGE_PATH`, bound to `app.storage.root` and read by
  `storage.StorageProperties`, so the same artefact works in every environment.
- The volume is persistent and survives container replacement (NFR-5). It is **not** servable:
  nothing maps it to a URL, and no static-resource handler points at it.
- Writes go to a temporary file in the same directory and are then moved into place atomically, so
  a crash mid-write leaves either nothing or the complete artefact — never a truncated file that a
  later reader would checksum and find wanting.
- The checksum in `submissions` is the tie-breaker if a file and its row ever disagree.

```mermaid
flowchart LR
    Row["submissions row<br/>stored_file_name = '4f2a…'<br/>checksum_sha256 = '9f86…'"]
    Root["UPLOAD_STORAGE_PATH volume<br/>(env-provided)"]
    File["4f2a… &nbsp;(the PDF bytes)"]

    Row -->|"resolve the stored name"| Root
    Root --> File
    File -.->|"re-hash and compare"| Row
```

**The row and the file cannot be committed together.** There is no distributed transaction between
the volume and MySQL; `SubmissionService` orders the two writes and registers a transaction
synchronization that removes the file if the row does not commit. The failure that remains
unhandled is the process dying between the write and the commit: no code runs, and the file is
orphaned. Reconciling the volume against the table is a sweeper, and it is not in the MVP.

## 7. Migration strategy

- Schema is created and evolved **only** through Flyway migrations under
  `src/main/resources/db/migration` (FR-27).
- Hibernate runs with `ddl-auto=validate`. It never creates or alters schema; a mismatch between an
  entity and the migrated schema fails startup rather than mutating the database.
- The migrations are `V1__baseline.sql` (the `users` table) and `V2__submissions.sql` (the
  `submissions` table). Each creates one table and the constraints and indexes that belong to it.
- Migrations are **forward-only**. A released migration is never edited; a correction is a new
  migration. `V1` was amended in place while the authentication milestone was in progress, which
  §2 records: it had never been applied in any environment, because Flyway's autoconfiguration was
  absent and the migration silently never ran. That window closed the moment a migration was
  applied anywhere, and nothing since has been edited in place.
- **Seed data is not a migration.** A migration runs in every environment, and demo accounts
  belong in one. `auth/DevelopmentUserSeeder` creates them, gated on two independent conditions:
  the bean exists only under the `dev` profile, and it acts only when `APP_SEED_ENABLED` is true.
  The default is `false`. Each account's password comes from its own environment variable, so an
  unset variable means the account is skipped rather than created with a guessable password. No
  plaintext credential is in Git, and none can be: there is no default password anywhere in the
  code. See [security.md §10](security.md#10-development-seed-accounts).
- The assignment catalogue that once appeared in this list is out of scope for the MVP
  (`mvp.md` §3 #2), and §3 records what replaced it.

## 8. Deliberately not modelled

| Not present | Reason |
| --- | --- |
| `assignments` table | The MVP takes the course code as free text (out of scope, `mvp.md` §3 #2) |
| A `roles` table | Three fixed roles; an enum column is sufficient |
| `submission_comments` | A decision carries one comment; threads are not an MVP concept |
| Resubmission linkage | Rejected is terminal (`mvp.md` §3 #1) |
| Soft-delete columns | Deletion is not an MVP operation |
| `created_by` / `updated_by` on every table | The history and audit tables already answer "who did what" |
