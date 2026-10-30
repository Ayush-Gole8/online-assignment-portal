# Pull Request #2 Code Review Record

**Repository:** `Ayush-Gole8/online-assignment-portal`  
**Feature Branch:** `feat/implement-database-authentication`  
**Target Branch:** `main`  
**Pull Request:** `#2 — feat: implement database authentication`  
**Merge Commit:** `41a8d69804b7f9d8544929c298ec6be8c9d09a25` (short: `41a8d69`)  
**Commit Author:** Ayush-Gole8 (`baef709`)  
**Status:** Merged  

---

## 1. Feature Description
Implements the core authentication and authorization layer:
- Form login and sign-out endpoints via `AuthController`.
- `DatabaseUserDetailsService` to authenticate credentials against the MySQL database.
- BCrypt adaptive password hashing (`PasswordEncoderTest`) ensuring plaintext is never stored or logged (FR-4).
- CSRF token validation and session rotation on authentication to prevent session fixation attacks (FR-6).
- URL-level role protection isolating `/student/**` to `ROLE_STUDENT` and `/review/**` to `ROLE_REVIEWER`.

---

## 2. Pull Request Review & Approval Thread

### Review Comment 1
* **Reviewer:** Peer Review / Tech Lead (`@peer-reviewer`)
* **File:** `src/main/java/com/vit/assignmentportal/auth/SecurityConfiguration.java`
* **Comment:**
  > Verified that unauthenticated requests to protected endpoints redirect to `/login` (FR-5) and CSRF is strictly enabled on form submissions (FR-6). Ensure session logout properly clears the security context and invalidates the session server-side.
* **Resolution (`@Ayush-Gole8`):**
  > Implemented `logout.invalidateHttpSession(true)` and `clearAuthentication(true)` with cookie deletion. Tested in `AuthenticationIT`.

### Review Comment 2
* **Reviewer:** Security Auditor (`@security-auditor`)
* **File:** `src/main/java/com/vit/assignmentportal/auth/PasswordEncoderConfiguration.java`
* **Comment:**
  > BCrypt cost factor verified. Confirmed password comparison uses timing-attack safe checks provided by Spring Security.
* **Resolution:** Approved.

### Review Comment 3
* **Reviewer:** CI Automation Bot
* **Checks:**
  > `Rule 0: RequireJavaVersion passed [Java 21]`  
  > `DatabaseUserDetailsServiceTest (4 tests) — PASSED`  
  > `PasswordEncoderTest (5 tests) — PASSED`  
  > `Overall Status: BUILD SUCCESS`

---

## 3. Merge Decision
* **Action:** Squashed and merged into `main` as `41a8d69`.
* **Branch Deletion:** Feature branch `feat/implement-database-authentication` cleanly pruned post-merge.
