# Task 04 — Session authentication and role authorization

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 04: Session authentication and role authorization |
| Objective | Authenticate browser users, protect state-changing forms, and enforce STUDENT/REVIEWER/ADMIN route permissions on every request. |
| Implementation | Spring Security form login uses database-backed users, BCrypt password hashes, server sessions, CSRF, session-fixation protection, and a default-authenticated route policy. Student ownership is enforced in queries. |
| Primary files | [src/main/java/com/vit/assignmentportal/config/SecurityConfiguration.java](../../src/main/java/com/vit/assignmentportal/config/SecurityConfiguration.java), [src/main/java/com/vit/assignmentportal/auth/DatabaseUserDetailsService.java](../../src/main/java/com/vit/assignmentportal/auth/DatabaseUserDetailsService.java), [src/main/java/com/vit/assignmentportal/auth/CurrentUserService.java](../../src/main/java/com/vit/assignmentportal/auth/CurrentUserService.java), [src/test/java/com/vit/assignmentportal/auth/AuthenticationIT.java](../../src/test/java/com/vit/assignmentportal/auth/AuthenticationIT.java), [src/test/java/com/vit/assignmentportal/auth/AuthorizationIT.java](../../src/test/java/com/vit/assignmentportal/auth/AuthorizationIT.java) |
| Command/demo | Windows: .\mvnw.cmd -B -Dit.test=AuthenticationIT,AuthorizationIT verify. Then demonstrate student access, reviewer access, anonymous redirect, and a role-denied route in the browser. |
| Expected result | Authentication and authorization integration tests pass; anonymous and wrong-role requests are refused, and CSRF/session behavior matches the security configuration. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture test reports and browser frames for successful login, denied access, and sign-out. Do not expose passwords or session cookies.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

