# Task 11 -- Selenium browser workflows

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 11: Selenium browser workflows |
| Objective | Prove a real browser can complete student and reviewer flows against the packaged application. |
| Implementation | Selenium 4 E2E test classes (StudentWorkflowE2E, ReviewerWorkflowE2E) use Page Objects for each page, explicit condition waits (not Thread.sleep), Selenium Manager for automatic ChromeDriver resolution, and E2EFailureArtifacts (a JUnit 5 extension) that captures screenshots and page source on failure. |
| Primary files | src/test/java/com/vit/assignmentportal/e2e/StudentWorkflowE2E.java, src/test/java/com/vit/assignmentportal/e2e/ReviewerWorkflowE2E.java, src/test/java/com/vit/assignmentportal/e2e/pages/, src/test/java/com/vit/assignmentportal/e2e/E2EFailureArtifacts.java, scripts/dev/run-e2e.cmd, scripts/ci/run-e2e.cmd |
| Command/demo | Windows with Java 21, Docker Desktop, and Chrome: scripts\dev\run-e2e.cmd |
| Expected result | Student upload and reviewer decision browser journeys complete against the started packaged app; reports are generated; failures retain screenshot/page-source artefacts. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture real browser screenshots for each completed workflow step and archive the Failsafe report. A screenshot of a single page does not prove the whole flow.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
