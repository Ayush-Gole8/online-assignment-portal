# Task 03 -- Java 21 and reproducible Maven build

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 03: Java 21 and reproducible Maven build |
| Objective | Make a clean checkout build with a consistent Maven version and reject a wrong Java toolchain early. |
| Implementation | Maven Wrapper pins Maven 3.9.16 via .mvn/wrapper/maven-wrapper.properties. maven-enforcer-plugin requires Java 21 ([21,22)) and Maven 3.9+, and fails early on a wrong toolchain. The Java compiler targets release 21. |
| Primary files | pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties, .editorconfig |
| Command/demo | Windows: .\mvnw.cmd -B clean verify. Full verification requires Docker for Testcontainers. A packaging-only check is .\mvnw.cmd -B clean package -DskipTests and does not prove tests pass. |
| Expected result | The clean verify build reports Enforcer success, compiles, and completes unit and integration tests with BUILD SUCCESS. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture the complete clean verify console output showing Enforcer rules passing, Surefire test count, and Failsafe test count, for the same commit SHA. Existing packaging-only output is not a substitute.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
