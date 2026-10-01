-- ===========================================================================
-- V2 — Submissions.
--
-- Scope: one row per uploaded coursework artefact, for the student submission
-- vertical slice.
--
-- This migration is a NEW file, not an edit of V1. V1 was still unreleased when
-- the identity model changed and was corrected in place; V2 adds a table that
-- never existed, so there is nothing to correct.
--
-- ---------------------------------------------------------------------------
-- WHERE THE BYTES LIVE
-- ---------------------------------------------------------------------------
-- This table holds METADATA ONLY. The PDF itself is written to the storage
-- volume under the name in `stored_file_name`, and never into MySQL (ADR-004).
--
-- A row and a file are two halves of one artefact, and no database can make the
-- two atomic with each other. The ordering that keeps them consistent is in
-- SubmissionService; the column that joins them back together is
-- `stored_file_name`, which is the same value StorageService used as the
-- on-disk file name.
--
-- ---------------------------------------------------------------------------
-- WHAT IS NOT HERE YET
-- ---------------------------------------------------------------------------
-- `submission_status_history` is described in docs/architecture/data-model.md §4
-- and is not created here. Nothing writes it until the review feature exists,
-- and it is the reviewer's decision that makes an append-only log meaningful.
-- Creating it now would be schema written ahead of the code that justifies it —
-- the same reasoning V1 followed when it stopped at `users`.
-- ===========================================================================

CREATE TABLE submissions
(
    -- A UUID, for the same reason as `users.id`: this identifier appears in the
    -- URL of the detail page. A sequential integer there would let anyone walk
    -- the range, and the ownership check deliberately answers 404 rather than
    -- 403 — so an enumeration attempt could not be distinguished from a typo.
    id                 VARCHAR(36)   NOT NULL,

    -- The owner. A foreign key, because a submission without a user is not a
    -- state this application can produce or should ever be able to read.
    --
    -- ON DELETE RESTRICT (the InnoDB default, stated for the reader): deleting
    -- a user who owns coursework would either orphan the rows or silently
    -- destroy somebody's academic record. Account removal is not an MVP
    -- operation, and when it arrives it will be a decision, not a cascade.
    student_id         VARCHAR(36)   NOT NULL,

    -- What the student called the work and which course it belongs to. Bounded
    -- by the column rather than unbounded: the widths below are mirrored as
    -- MAX_TITLE_LENGTH and MAX_COURSE_CODE_LENGTH in SubmissionService, which
    -- refuses an over-long value with a message instead of letting MySQL
    -- truncate it silently.
    title              VARCHAR(200)  NOT NULL,
    course_code        VARCHAR(20)   NOT NULL,

    -- The file name as it was on the student's machine. DISPLAY METADATA ONLY:
    -- it is stored so a reviewer can see what the student called the file, and
    -- it is NEVER interpolated into a filesystem path (CLAUDE.md §7 rule 5).
    -- It is normalised before it gets here — directory components and control
    -- characters removed — by FileValidationService.safeDisplayName.
    original_file_name VARCHAR(255)  NOT NULL,

    -- The server-generated name of the file on the volume: a bare UUID, with no
    -- extension. UNIQUE, because it is the only link between a row and its
    -- bytes; two rows pointing at one file would mean one artefact overwriting
    -- another. Immutable in the entity — rewriting it would orphan the file it
    -- names.
    stored_file_name   VARCHAR(36)   NOT NULL,

    -- The media type VALIDATED at upload time, not the string the client
    -- claimed. Always `application/pdf` in the MVP, and stored anyway so that
    -- lifting the single-format restriction is a validation change rather than
    -- a migration.
    content_type       VARCHAR(100)  NOT NULL,

    file_size_bytes    BIGINT        NOT NULL,

    -- CHAR(64), not VARCHAR: a SHA-256 hex digest has exactly one length. The
    -- column collation is case-insensitive, so the application writes lowercase
    -- and the value still compares equal either way.
    checksum_sha256    CHAR(64)      NOT NULL,

    status             VARCHAR(16)   NOT NULL,

    created_at         DATETIME(6)   NOT NULL,
    -- Unlike `users`, this table has an updated_at that genuinely changes: the
    -- optimistic lock bumps it, and the review decision will too.
    updated_at         DATETIME(6)   NOT NULL,

    -- The reviewer's decision. All three are NULL until a decision is recorded,
    -- which nothing does in this milestone — the review feature owns that
    -- transition. They are created now because the entity that describes a
    -- submission is being designed now, and splitting one table's description
    -- across two migrations would be the more confusing choice.
    reviewed_at        DATETIME(6)   NULL,
    reviewer_id        VARCHAR(36)   NULL,
    rejection_reason   VARCHAR(2000) NULL,

    -- JPA's optimistic lock. Hibernate increments it on every update and adds it
    -- to the WHERE clause, so a second writer holding a stale copy updates
    -- nothing and is told so. It matters the moment two reviewers open the same
    -- submission: a status transition must never be applied twice.
    version            BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pk_submissions PRIMARY KEY (id),

    CONSTRAINT uq_submissions_stored_file_name UNIQUE (stored_file_name),

    CONSTRAINT fk_submissions_student_id
        FOREIGN KEY (student_id) REFERENCES users (id) ON DELETE RESTRICT,

    CONSTRAINT fk_submissions_reviewer_id
        FOREIGN KEY (reviewer_id) REFERENCES users (id) ON DELETE RESTRICT,

    -- The three lifecycle states. The database is the backstop that survives a
    -- bug in the service; the application validates first and produces the
    -- friendly error (ADR-003).
    CONSTRAINT ck_submissions_status
        CHECK (status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),

    -- The 10 MB limit (FR-7), matching spring.servlet.multipart.max-file-size
    -- and FileValidationService.MAX_FILE_SIZE_BYTES. The upper bound is the
    -- backstop; the lower bound catches the row that would otherwise be stored
    -- for an upload that carried no bytes at all.
    CONSTRAINT ck_submissions_file_size
        CHECK (file_size_bytes > 0 AND file_size_bytes <= 10485760),

    -- A submission is either undecided — SUBMITTED, with no reviewer and no
    -- decision time — or decided, with both. This is the invariant from
    -- docs/architecture/data-model.md §3, and stating it here means the review
    -- feature cannot half-record a decision: it must write the status, the
    -- reviewer, and the time together, or not at all.
    CONSTRAINT ck_submissions_decision
        CHECK (
            (status = 'SUBMITTED' AND reviewed_at IS NULL AND reviewer_id IS NULL)
                OR (status <> 'SUBMITTED' AND reviewed_at IS NOT NULL AND reviewer_id IS NOT NULL)
            )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Coursework metadata. The bytes live on the storage volume, addressed by stored_file_name (ADR-004).';

-- Serves the student's own list: filter on the owner, sort by recency. One
-- index rather than two, because (student_id, created_at) answers both halves.
CREATE INDEX idx_submissions_student_id_created_at
    ON submissions (student_id, created_at);

-- Serves the reviewer queue (FR-16), which is not built yet. The index is
-- created with the table because adding it later means an ALTER on a table that
-- by then holds data; the cost of having it early is one B-tree that is written
-- on every insert and read by nothing. Declaring it now is a deliberate
-- exception to "no schema ahead of the code", and it is recorded here as one.
CREATE INDEX idx_submissions_status_created_at
    ON submissions (status, created_at);
