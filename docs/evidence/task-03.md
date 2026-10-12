# Task 03 — Java 21 and reproducible Maven build

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 03: Java 21 and reproducible Maven build |
| Objective | Make a clean checkout build with a consistent Maven version and reject a wrong Java toolchain early. |
| Implementation | Maven Wrapper pins Maven 3.9.16. Maven Enforcer requires Java 21 and Maven 3.9+, and the Java compiler targets release 21. |
| Primary files | [pom.xml](../../pom.xml), [mvnw](../../mvnw), [mvnw.cmd](../../mvnw.cmd), [.mvn/wrapper/maven-wrapper.properties](../../.mvn/wrapper/maven-wrapper.properties), [.editorconfig](../../.editorconfig) |
| Command/demo | Windows: .\mvnw.cmd -B clean verify. Full verification requires Docker for Testcontainers. A packaging-only check is .\mvnw.cmd -B clean verify -DskipTests and does not prove tests pass. |
| Expected result | The clean verify build reports Enforcer success, compiles, and completes unit and integration tests with BUILD SUCCESS. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture the complete clean verify console output and Surefire/Failsafe summaries for the same commit. Existing packaging-only output is not a substitute.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

