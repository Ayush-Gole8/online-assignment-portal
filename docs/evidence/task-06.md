# Task 06 — Persistent file storage and SHA-256 integrity

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 06: Persistent file storage and SHA-256 integrity |
| Objective | Store file bytes independently from database metadata using server-generated names, and record a digest that identifies the accepted bytes. |
| Implementation | StorageIdentifier restricts names to generated UUIDs. StorageService confines paths to the configured root and performs file I/O. ChecksumService computes SHA-256 from the same byte array that is stored. |
| Primary files | [src/main/java/com/vit/assignmentportal/storage/StorageIdentifier.java](../../src/main/java/com/vit/assignmentportal/storage/StorageIdentifier.java), [src/main/java/com/vit/assignmentportal/storage/StorageService.java](../../src/main/java/com/vit/assignmentportal/storage/StorageService.java), [src/main/java/com/vit/assignmentportal/storage/ChecksumService.java](../../src/main/java/com/vit/assignmentportal/storage/ChecksumService.java), [src/main/java/com/vit/assignmentportal/submission/Submission.java](../../src/main/java/com/vit/assignmentportal/submission/Submission.java), [src/test/java/com/vit/assignmentportal/storage/StorageServiceTest.java](../../src/test/java/com/vit/assignmentportal/storage/StorageServiceTest.java), [src/test/java/com/vit/assignmentportal/storage/ChecksumServiceTest.java](../../src/test/java/com/vit/assignmentportal/storage/ChecksumServiceTest.java) |
| Command/demo | Windows: .\mvnw.cmd -B -Dtest=StorageIdentifierTest,StorageServiceTest,ChecksumServiceTest test. In the demo, upload and download a sample PDF and compare the download digest to the recorded digest. |
| Expected result | Path traversal is not possible through a storage identifier; stored bytes round-trip; checksum tests match known vectors and persisted metadata. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture test output and a sanitized upload/download checksum demonstration using a synthetic PDF.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

