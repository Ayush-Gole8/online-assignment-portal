# Task 10 — Unit and integration test design

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 10: Unit and integration test design |
| Objective | Use fast unit tests for rules and real integration tests where MySQL, Spring Security wiring, or transactions matter. |
| Implementation | JUnit 5 tags, Surefire, Failsafe, Testcontainers MySQL, shared test fixtures, and fixed Clock injection divide the suite into unit, integration, and E2E layers. |
| Primary files | [pom.xml](../../pom.xml), [src/test/java/com/vit/assignmentportal/support/TestCategories.java](../../src/test/java/com/vit/assignmentportal/support/TestCategories.java), [src/test/java/com/vit/assignmentportal/support/TestUserFactory.java](../../src/test/java/com/vit/assignmentportal/support/TestUserFactory.java), [src/test/java/com/vit/assignmentportal/support/SubmissionTestData.java](../../src/test/java/com/vit/assignmentportal/support/SubmissionTestData.java), [src/test/java/com/vit/assignmentportal/MySqlIntegrationTest.java](../../src/test/java/com/vit/assignmentportal/MySqlIntegrationTest.java), [docs/testing/test-strategy.md](../../docs/testing/test-strategy.md) |
| Command/demo | Windows: .\mvnw.cmd -B clean verify with Docker available. |
| Expected result | Surefire and Failsafe reports show every intended test category executed with no failures or errors; results correspond to the same commit SHA. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — archive raw Surefire/Failsafe XML and a short console summary with date, commit SHA, Java/Maven versions, and Docker availability.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

