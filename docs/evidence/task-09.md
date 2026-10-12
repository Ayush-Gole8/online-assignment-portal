# Task 09 -- Reviewer decisions, history, and concurrency

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 09: Reviewer decisions, history, and concurrency |
| Objective | Provide a reviewer queue and one auditable terminal decision while preventing conflicting concurrent decisions. |
| Implementation | ReviewService checks decision policy (REJECTED requires a comment; APPROVED must not carry one). SubmissionService owns the state transition inside a @Transactional method. SubmissionStatusHistory records each workflow transition with actor, timestamp, previous status, new status, and comment. @Version on Submission provides optimistic locking: a second concurrent reviewer gets OptimisticLockingFailureException, which the application maps to a user-visible message. |
| Primary files | src/main/java/com/vit/assignmentportal/review/ReviewService.java, src/main/java/com/vit/assignmentportal/review/ReviewController.java, src/main/java/com/vit/assignmentportal/submission/Submission.java, src/main/java/com/vit/assignmentportal/submission/SubmissionStatusHistory.java, src/main/resources/db/migration/V3__submission_status_history.sql, src/test/java/com/vit/assignmentportal/review/ReviewWorkflowIT.java, src/test/java/com/vit/assignmentportal/submission/SubmissionConcurrencyIT.java |
| Command/demo | Windows: .\mvnw.cmd -B -Dit.test=ReviewWorkflowIT,SubmissionConcurrencyIT verify with Docker available. Demonstrate approval and rejection paths with separate synthetic submissions. |
| Expected result | Only permitted roles see review routes; a valid decision is final; history records the transition; a stale concurrent reviewer loses without overwriting the winning decision. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture the workflow/concurrency reports and reviewer queue/detail plus student-visible result. Do not represent status history as FR-24 audit logging.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
