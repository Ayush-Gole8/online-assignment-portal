# Task 07 — MySQL schema and Flyway migrations

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 07: MySQL schema and Flyway migrations |
| Objective | Keep schema evolution explicit and reproducible against the same MySQL family used in deployment. |
| Implementation | Versioned V1–V3 Flyway migrations own schema changes. Hibernate uses validate, not DDL generation. Repositories query MySQL through Spring Data JPA. |
| Primary files | [src/main/resources/db/migration/V1__baseline.sql](../../src/main/resources/db/migration/V1__baseline.sql), [src/main/resources/db/migration/V2__submissions.sql](../../src/main/resources/db/migration/V2__submissions.sql), [src/main/resources/db/migration/V3__submission_status_history.sql](../../src/main/resources/db/migration/V3__submission_status_history.sql), [src/main/resources/application.yml](../../src/main/resources/application.yml), [src/main/java/com/vit/assignmentportal/submission/SubmissionRepository.java](../../src/main/java/com/vit/assignmentportal/submission/SubmissionRepository.java), [src/test/java/com/vit/assignmentportal/ApplicationContextIT.java](../../src/test/java/com/vit/assignmentportal/ApplicationContextIT.java) |
| Command/demo | Windows: .\mvnw.cmd -B -Dit.test=ApplicationContextIT verify with Docker available. |
| Expected result | Testcontainers starts MySQL 8.4; all migrations apply, Hibernate validates the mappings, and migration/schema constraint assertions pass. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture the Failsafe report and sanitized Flyway migration output identifying the MySQL image/version and applied migration versions.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

