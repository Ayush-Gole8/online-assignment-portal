# Task 04 -- Session authentication and role authorisation

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 04: Session authentication and role authorisation |
| Objective | Authenticate browser users, protect state-changing forms, and enforce STUDENT/REVIEWER/ADMIN route permissions on every request. |
| Implementation | Spring Security form login uses database-backed users (DatabaseUserDetailsService), BCrypt password hashes, server sessions, CSRF enabled globally, session-fixation protection on login, and a default-deny route policy. Student ownership is enforced in repository queries (WHERE owner_id=?) so another student's submission is never loaded. |
| Primary files | src/main/java/com/vit/assignmentportal/config/SecurityConfiguration.java, src/main/java/com/vit/assignmentportal/auth/DatabaseUserDetailsService.java, src/main/java/com/vit/assignmentportal/auth/CurrentUserService.java, src/test/java/com/vit/assignmentportal/auth/AuthenticationIT.java, src/test/java/com/vit/assignmentportal/auth/AuthorizationIT.java |
| Command/demo | Windows: .\mvnw.cmd -B -Dit.test=AuthenticationIT,AuthorizationIT verify. Then demonstrate student access, reviewer access, anonymous redirect, and a role-denied route in the browser. |
| Expected result | Authentication and authorisation integration tests pass; anonymous and wrong-role requests are refused with the expected redirect or 403; CSRF/session behaviour matches the security configuration. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture test reports and browser frames for successful login, denied access, and sign-out. Do not expose passwords or session cookies.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
