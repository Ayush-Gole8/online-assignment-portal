-- ===========================================================================
-- V3 — Submission status history.
--
-- Scope: one append-only row per status transition, for the reviewer decision
-- slice (FR-15, FR-21).
--
-- A NEW migration, not an edit of V1 or V2. Both of those have now been applied
-- — the rule in V1's header froze them the moment that happened — so a
-- correction or an addition is a new file. This one only creates a table that
-- never existed.
--
-- ---------------------------------------------------------------------------
-- WHY THIS TABLE IS THE RECORD OF TRUTH
-- ---------------------------------------------------------------------------
-- `submissions` carries the CURRENT status, plus `reviewed_at` and
-- `reviewer_id` denormalised onto the row so the reviewer queue does not have
-- to join a log to render a list. That makes the submissions row fast to read
-- and lossy: it says what the state is now, never how it got there.
--
-- This table is the other half. It is written in the same transaction as every
-- status change (SubmissionService.submit and SubmissionService.recordDecision),
-- so the two can never disagree, and it is APPEND-ONLY: no UPDATE and no DELETE
-- is issued against it anywhere in the application. A correction would be a new
-- compensating row. That is what makes it worth trusting as an audit record.
--
-- ---------------------------------------------------------------------------
-- WHAT IS STILL NOT HERE
-- ---------------------------------------------------------------------------
-- `audit_events` (FR-24) is a different concern and is still absent: this table
-- records WORKFLOW transitions, while that one records security events such as
-- sign-in success and failure. See docs/architecture/data-model.md §5 and
-- docs/architecture/security.md §12.
-- ===========================================================================

CREATE TABLE submission_status_history
(
    -- A UUID, like every other identifier in this schema, rather than an
    -- AUTO_INCREMENT surrogate. The data model originally specified a BIGINT
    -- here; VARCHAR(36) is what shipped, so that the application assigns every
    -- key in every table and nothing depends on a database-generated value.
    -- See data-model.md §4 for the recorded deviation.
    id              VARCHAR(36)   NOT NULL,

    -- The submission this transition belongs to. ON DELETE RESTRICT, matching
    -- `submissions.student_id`: deleting a submission that has history would
    -- destroy the record of what was decided about it, and deletion is not an
    -- MVP operation in the first place.
    submission_id   VARCHAR(36)   NOT NULL,

    -- NULL for the creation row, which is what marks the beginning of a
    -- submission's lifecycle: from nothing, to SUBMITTED. Every later row has
    -- both sides populated.
    from_status     VARCHAR(16)   NULL,
    to_status       VARCHAR(16)   NOT NULL,

    -- Who caused the transition: the student for the creation row, the reviewer
    -- for a decision. NOT NULL — a transition nobody is accountable for is the
    -- thing an audit table exists to prevent.
    actor_id        VARCHAR(36)   NOT NULL,

    -- The reviewer's comment. NULL-able here because the creation row and an
    -- approval carry none; the rule that a REJECTION must have one is enforced
    -- in ReviewService, where it can produce a sentence a reviewer can act on,
    -- and again by the Submission entity. Width matches
    -- `submissions.rejection_reason`, because it holds the same text.
    note            VARCHAR(2000) NULL,

    created_at      DATETIME(6)   NOT NULL,

    CONSTRAINT pk_submission_status_history PRIMARY KEY (id),

    CONSTRAINT fk_submission_status_history_submission_id
        FOREIGN KEY (submission_id) REFERENCES submissions (id) ON DELETE RESTRICT,

    CONSTRAINT fk_submission_status_history_actor_id
        FOREIGN KEY (actor_id) REFERENCES users (id) ON DELETE RESTRICT,

    -- The same vocabulary as `ck_submissions_status`, applied to both columns.
    -- `from_status` admits NULL as well, for the creation row.
    CONSTRAINT ck_submission_status_history_from_status
        CHECK (from_status IS NULL
            OR from_status IN ('SUBMITTED', 'APPROVED', 'REJECTED')),

    CONSTRAINT ck_submission_status_history_to_status
        CHECK (to_status IN ('SUBMITTED', 'APPROVED', 'REJECTED'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Append-only log of every submission status transition. The audit of record for the workflow (FR-21).';

-- Serves the only read this table has: one submission's history, oldest first.
-- (submission_id, created_at) answers both the filter and the sort.
CREATE INDEX idx_submission_status_history_submission_id_created_at
    ON submission_status_history (submission_id, created_at);
