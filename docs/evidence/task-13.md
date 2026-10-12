# Task 13 -- Jenkins continuous integration

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 13: Jenkins continuous integration |
| Objective | Run the same compilation, unit, integration, Selenium, packaging, and image checks on a separate CI agent with reports retained. |
| Implementation | The Declarative Jenkinsfile validates the Windows agent environment (java/git/docker/compose versions), runs Maven stages through separate Surefire/Failsafe executions, starts a test environment for the browser suite, archives reports/screenshots/JAR, and gates Publish/Deploy on a successful build and a vX.Y.Z tag. All long sequences are in scripts/ci/ and scripts/ops/ so they can be run locally. |
| Primary files | Jenkinsfile, scripts/ci/, scripts/ops/, docs/operations/jenkins-ci.md, docs/testing/ci-quality-gates.md |
| Command/demo | Open the configured Jenkins multibranch job; build a PR branch, merge it, and inspect each named pipeline stage. Jenkins configuration and agent setup are deployment prerequisites. |
| Expected result | The PR build shows Build, Unit Tests, Integration Tests, Package, Start Test Environment, Selenium E2E, Publish Reports, Release Version, Docker Build stages. Publish Image and Deploy stages are skipped (no vX.Y.Z tag). A failed test is red and cannot reach Publish/Deploy. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture Jenkins stage view and console/test reports for the exact Git SHA, including a feature/PR build showing Publish/Deploy skipped.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
