-- ===========================================================================
-- V1 — Baseline schema for the Online Assignment Portal.
--
-- Scope: the IDENTITY foundation only.
--
-- This migration deliberately stops at `users`. The remaining tables described
-- in docs/architecture/data-model.md — submissions, submission_status_history
-- and audit_events — are created by later migrations, alongside the features
-- that own them. Creating them here would be schema written ahead of the code
-- that justifies it.
--
-- `users` is the foundation because every other table references it: it is the
-- root of the foreign-key graph and the prerequisite for the authentication
-- layer that sits on top of it.
--
-- ---------------------------------------------------------------------------
-- AMENDED IN THE AUTHENTICATION MILESTONE — and why that is allowed
-- ---------------------------------------------------------------------------
-- The rule is "never edit a migration once it has been applied anywhere". This
-- file has been applied to nowhere:
--
--   * It is uncommitted work on a feature branch.
--   * The Flyway autoconfiguration was absent on Spring Boot 4 (ADR-003), so
--     `V1` never executed — not locally, not in CI, not in a Testcontainer.
--     Flyway reported no error because Flyway never started at all.
--
-- The authentication milestone replaced the identity model: a UUID primary key,
-- `email` as the login identifier, and `updated_at` for change tracking. While
-- the migration is still unreleased, correcting it in place is the honest
-- change. The alternative is a `V2` that drops and re-adds every column of a
-- table that has never held a row, which documents a migration history that
-- never happened.
--
-- The moment this file is applied anywhere — including the first green
-- `./mvnw verify` — it freezes. Any further change is a new migration.
-- ===========================================================================

CREATE TABLE users
(
    -- A UUID, not an auto-increment integer, because a user's identifier appears
    -- in URLs, in audit rows, and in log lines. A sequential integer there
    -- announces how many users exist and invites enumeration.
    --
    -- Stored as VARCHAR(36) rather than BINARY(16). The 20 bytes saved per row
    -- are worth less here than being able to read an identifier in a SELECT, a
    -- schema dump, or a pipeline log without a decoding step. The mapping is
    -- pinned by common/UuidToVarcharConverter, so this column's type is the
    -- migration's decision rather than the ORM's — Hibernate's default UUID
    -- representation for MySQL has changed between major versions, and
    -- `ddl-auto: validate` turns that kind of surprise into a startup failure.
    id            VARCHAR(36)  NOT NULL,

    -- The login identifier. 254 is the maximum length of an address in the
    -- RFC 5321 path, so the column can never silently truncate a valid address.
    email         VARCHAR(254) NOT NULL,

    -- BCrypt output only. Never plaintext, never reversible. 100 leaves room for
    -- a future encoder change — a BCrypt hash is 60 characters, but a column
    -- sized to exactly today's algorithm is a migration waiting to happen.
    password_hash VARCHAR(100) NOT NULL,

    role          VARCHAR(16)  NOT NULL,

    -- Disabled users cannot authenticate. Checked at load time by
    -- DatabaseUserDetailsService, so revocation takes effect on that user's
    -- next request — the concrete benefit of server-side session state.
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,

    CONSTRAINT pk_users PRIMARY KEY (id),

    -- Login identifiers are compared case-insensitively, enforced by the column
    -- collation (utf8mb4_0900_ai_ci) rather than by lower-casing in application
    -- code, so the guarantee holds for any writer (data-model.md §2). Two people
    -- cannot register "A@x.com" and "a@x.com" as separate accounts.
    CONSTRAINT uq_users_email UNIQUE (email),

    -- The three MVP roles. The database is the backstop that survives a bug in
    -- the service layer; the application validates first and produces the
    -- friendly error (ADR-003).
    CONSTRAINT ck_users_role CHECK (role IN ('STUDENT', 'REVIEWER', 'ADMIN'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Portal users and their single MVP role. Passwords are stored only as a BCrypt hash.';
