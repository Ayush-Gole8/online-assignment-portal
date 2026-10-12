# Task 10 -- Unit and integration test design

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 10: Unit and integration test design |
| Objective | Use fast unit tests for business rules and real integration tests where MySQL, Spring Security wiring, or transactions matter. |
| Implementation | JUnit 5 @Tag annotations (unit, integration, e2e) partition the suite. Surefire runs unit tests; Failsafe runs integration tests. Testcontainers (MySqlIntegrationTest base class) provides a shared MySQL 8.4 container. TestUserFactory, SubmissionTestData, and fixed Clock injection are shared test fixtures. The pom.xml excludedGroups configuration prevents cross-phase leakage. |
| Primary files | pom.xml, src/test/java/com/vit/assignmentportal/support/TestCategories.java, src/test/java/com/vit/assignmentportal/support/TestUserFactory.java, src/test/java/com/vit/assignmentportal/support/SubmissionTestData.java, src/test/java/com/vit/assignmentportal/MySqlIntegrationTest.java, docs/testing/strategy.md |
| Command/demo | Windows: .\mvnw.cmd -B clean verify with Docker available. |
| Expected result | Surefire and Failsafe reports show every intended test category executed with no failures or errors; results correspond to the same commit SHA. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Archive raw Surefire/Failsafe XML and a short console summary with date, commit SHA, Java/Maven versions, and Docker availability.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
