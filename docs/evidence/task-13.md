# Task 13 — Jenkins continuous integration

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 13: Jenkins continuous integration |
| Objective | Run the same compilation, unit, integration, Selenium, packaging, and image checks on a separate CI agent with reports retained. |
| Implementation | The Declarative Jenkinsfile validates the Windows agent, runs Maven stages, starts a test environment, archives reports/screenshots/JAR, and gates publish/deploy on success and a release tag. |
| Primary files | [Jenkinsfile](../../Jenkinsfile), [scripts/ci/](../../scripts/ci/), [scripts/ops/](../../scripts/ops/), [docs/operations/jenkins-ci.md](../../docs/operations/jenkins-ci.md), [docs/testing/ci-quality-gates.md](../../docs/testing/ci-quality-gates.md) |
| Command/demo | Open the configured Jenkins multibranch job; build a PR branch, merge it, and inspect each named pipeline stage. Jenkins configuration and agent setup are deployment prerequisites. |
| Expected result | The PR build shows build, unit, integration, package, E2E, artifact publication, and local Docker build stages. A failed test is red and cannot publish/deploy; an ordinary branch does not publish. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture Jenkins stage view and console/test reports for the exact Git SHA, including a feature/PR build with publish/deploy skipped.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

