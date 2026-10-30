# Task 10: Continuous Testing in Jenkins

**Evidence status:** CAPTURED

| Field | Details |
| --- | --- |
| Task | 10: Continuous Testing in Jenkins |
| Objective | Integrate automated verification across Unit, Integration, and Selenium E2E suites into Jenkins, enforce quality gates, and demonstrate defect detection and resolution. |
| Implementation | Jenkinsfile defines a multi-stage verification pipeline with catchError blocks and conditional stage gates (when { expression { currentBuild.currentResult == 'SUCCESS' } }). Downstream packaging (Docker Build) and Deploy stages execute only when all preceding tests succeed. Defect demonstration tests the gate by breaking the multipart form encoding in upload.html. |
| Primary files | Jenkinsfile, docs/evidence/defect-demonstration.md, src/main/resources/templates/submission/upload.html, pom.xml |
| Command/demo | Jenkins job execution and local pipeline simulation of defect induction, quality gate failure, defect correction, and successful green rerun. |
| Expected result | Defect causes Selenium E2E stage to fail, captures failure screenshots, and stops downstream deployment; defect correction results in full pass across 134 tests and clears deployment gate. |

## Defect Demonstration and Quality Gate Verification

1. **Defect Induction:**
   - Planted defect in `src/main/resources/templates/submission/upload.html` by removing `enctype="multipart/form-data"`.
   - Result: Unit and Integration tests passed, but Selenium E2E stage failed with `AssertionError`.
   - Automated failure artifacts (`screenshot.png` and `page-source.html`) were saved to `target/e2e-artifacts/studentCanLoginAndSubmitValidPdf/`.
   - Quality gate tripped: `currentBuild.currentResult` set to `FAILURE`. Downstream `Docker Build`, `Publish Image`, and `Deploy` stages were skipped.

2. **Defect Correction and Clean Rerun:**
   - Restored `enctype="multipart/form-data"` in `upload.html`.
   - Pipeline re-executed:
     * Unit Tests: 27/27 passed.
     * Integration Tests: 103/103 passed against MySQL 8.4 container.
     * Selenium E2E Tests: 4/4 passed (27.481 s).
     * JUnit Report: 134/134 passed, 0 failures, 0 errors, 0 skipped.
     * Quality gate passed: `currentBuild.currentResult` evaluated to `SUCCESS`, unlocking container build and deployment.

## Evidence Artifacts

1. **Failure Quality Gate Trip Screenshot:**
   `docs/evidence/10-testing/2026-10-20-jenkins-test-quality-gate-fail.png`
2. **Success Test Report & Rerun Screenshot:**
   `docs/evidence/10-testing/2026-10-20-jenkins-test-report-pass.png`
3. **Automated Browser Failure Capture:**
   `target/e2e-artifacts/studentCanLoginAndSubmitValidPdf/screenshot.png`
