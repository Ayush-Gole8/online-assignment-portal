-- ===========================================================================
-- V1 — Baseline schema for the Online Assignment Portal.
--
-- Scope: the IDENTITY foundation only.
--
-- This migration deliberately stops at `users`. The remaining tables described
-- in docs/architecture/data-model.md — submissions, submission_status_history
-- and audit_events — are created by later migrations, alongside the features
-- that own them. Creating them here would be schema written ahead of the code
-- that justifies it, which is exactly what this milestone is told not to do.
--
-- `users` is the foundation because every other table references it: it is the
-- root of the foreign-key graph and the prerequisite for the authentication
-- milestone that follows.
--
-- Migrations are forward-only (ADR-003). Never edit this file once it has been
-- applied anywhere; correct it with a new migration.
-- ===========================================================================

CREATE TABLE users
(
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(128) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    DATETIME(6)  NOT NULL,

    CONSTRAINT pk_users PRIMARY KEY (id),

    -- Login identifiers are compared case-insensitively. Enforced by the column
    -- collation (utf8mb4_0900_ai_ci) rather than by lower-casing in application
    -- code, so the guarantee holds for any writer (data-model.md §2).
    CONSTRAINT uq_users_username UNIQUE (username),

    -- The three MVP roles. The database is the backstop that survives a bug in
    -- the service layer; the application validates first and produces the
    -- friendly error (ADR-003).
    CONSTRAINT ck_users_role CHECK (role IN ('STUDENT', 'REVIEWER', 'ADMIN'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci
  COMMENT = 'Portal users and their single MVP role. Passwords are stored only as a BCrypt hash.';
