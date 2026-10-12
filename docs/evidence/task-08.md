# Task 08 -- Student submission workflow

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 08: Student submission workflow |
| Objective | Let a student submit, list, inspect, and download only their own coursework. |
| Implementation | SubmissionController delegates to SubmissionService. Read models (SubmissionSummary) are mapped in the service layer. Owner identifiers come from the authenticated principal and owner-scoped lookups (WHERE id=? AND owner_id=?) return not-found for another user's item, so cross-student access is structurally impossible. |
| Primary files | src/main/java/com/vit/assignmentportal/submission/SubmissionController.java, src/main/java/com/vit/assignmentportal/submission/StudentDashboardController.java, src/main/java/com/vit/assignmentportal/submission/SubmissionService.java, src/main/java/com/vit/assignmentportal/submission/SubmissionSummary.java, src/test/java/com/vit/assignmentportal/submission/SubmissionAccessIT.java, src/test/java/com/vit/assignmentportal/submission/StudentDashboardIT.java |
| Command/demo | Windows: .\mvnw.cmd -B -Dit.test=SubmissionUploadIT,SubmissionAccessIT,StudentDashboardIT verify with Docker available. Use the browser to upload a synthetic PDF, view its detail page, and download it. |
| Expected result | The student sees their submission and status; the stored file downloads as an attachment with Content-Disposition: attachment; a second student's record is not visible. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture dashboard, upload success, detail/history, download response, and isolation test output. Use synthetic identities and files.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
