# Task 09: Selenium Test Design and Local Execution

**Evidence status:** CAPTURED

| Field | Details |
| --- | --- |
| Task | 09: Selenium Test Design and Local Execution |
| Objective | Design and execute end-to-end Selenium WebDriver test suites covering critical user journeys with Page Object Model and automated failure artifacts. |
| Implementation | Selenium 4 test suites (StudentWorkflowE2E, ReviewerWorkflowE2E) utilize Page Object Model classes (LoginPage, StudentDashboardPage, SubmissionPage, SubmissionDetailsPage, ReviewerQueuePage, ReviewerDetailsPage) with explicit condition waits. A custom JUnit 5 extension (E2EFailureArtifacts) captures screenshots and page source on any assertion failure. |
| Primary files | src/test/java/com/vit/assignmentportal/e2e/StudentWorkflowE2E.java, src/test/java/com/vit/assignmentportal/e2e/ReviewerWorkflowE2E.java, src/test/java/com/vit/assignmentportal/e2e/pages/, src/test/java/com/vit/assignmentportal/e2e/E2EFailureArtifacts.java, pom.xml |
| Command/demo | mvn -Pe2e test-compile failsafe:integration-test@e2e-tests "-De2e.baseUrl=http://localhost:8081" |
| Expected result | All 4 Selenium E2E tests pass with zero failures and zero errors; failure extension generates screenshots and page source when assertions fail. |

## Execution Summary and Verification

The complete Selenium E2E suite executed locally against the live application running on `http://localhost:8081`:

* **ReviewerWorkflowE2E:**
  - `reviewerCanApproveSubmission`: PASS (8.564 s)
  - `reviewerCanRejectSubmissionWithReason`: PASS
* **StudentWorkflowE2E:**
  - `studentCanLoginAndSubmitValidPdf`: PASS (5.332 s)
  - `studentCannotSubmitInvalidFile`: PASS
* **Suite Total:** Tests run: 4, Failures: 0, Errors: 0, Skipped: 0. Result: BUILD SUCCESS (27.481 s).

## Evidence Artifacts

1. **Test Execution Console Screenshot:**
   `docs/evidence/09-selenium/2026-10-20-selenium-e2e-pass.png`
2. **Failure Artifact Capture Demonstration:**
   `target/e2e-artifacts/studentCanLoginAndSubmitValidPdf/screenshot.png` (captured via JUnit 5 E2EFailureArtifacts extension).
