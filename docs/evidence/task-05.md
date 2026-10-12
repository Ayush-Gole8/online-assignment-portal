# Task 05 — Secure PDF upload validation

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 05: Secure PDF upload validation |
| Objective | Reject malformed or oversized uploads server-side and prevent client filenames or claimed MIME types from controlling storage. |
| Implementation | Upload validation checks configured size, PDF extension, content type policy, and PDF signature bytes. Display filenames are normalized, and the servlet multipart limit is aligned with the application limit. |
| Primary files | [src/main/java/com/vit/assignmentportal/storage/FileValidationService.java](../../src/main/java/com/vit/assignmentportal/storage/FileValidationService.java), [src/main/java/com/vit/assignmentportal/storage/UploadProperties.java](../../src/main/java/com/vit/assignmentportal/storage/UploadProperties.java), [src/main/java/com/vit/assignmentportal/submission/SubmissionController.java](../../src/main/java/com/vit/assignmentportal/submission/SubmissionController.java), [src/test/java/com/vit/assignmentportal/storage/FileValidationServiceTest.java](../../src/test/java/com/vit/assignmentportal/storage/FileValidationServiceTest.java), [src/test/java/com/vit/assignmentportal/submission/SubmissionUploadIT.java](../../src/test/java/com/vit/assignmentportal/submission/SubmissionUploadIT.java) |
| Command/demo | Windows: .\mvnw.cmd -B -Dtest=FileValidationServiceTest test. For the HTTP path, run .\mvnw.cmd -B -Dit.test=SubmissionUploadIT verify with Docker available. |
| Expected result | Valid PDF bytes are accepted; oversized, wrong-extension, mismatched-signature, missing-file, and unsafe-name cases are refused without storing a submission. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture the test report and a real browser/server-side refusal showing a field-level validation message. Redact filenames if they contain personal data.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

