# ADR-005 — Session authentication with Spring Security

- **Status:** Accepted
- **Date:** 2026-10-01
- **Deciders:** Repository maintainer
- **Related:** [ADR-002](ADR-002-spring-boot-embedded-tomcat.md) · [SECURITY.md](../../SECURITY.md) · [MVP requirements](../requirements/mvp.md)

## Context

The portal serves three roles over a **server-rendered Thymeleaf UI** — an HTML form in, an
HTML page out. It is a browser application, not a single-page application, and it exposes no
JSON API (`mvp.md` §3 #9). Authentication must therefore fit a request/response, form-post,
redirect-oriented interaction model, and the UI must degrade gracefully without JavaScript
(NFR-2).

Two mainstream approaches:

1. **Session-based authentication** — the server holds authenticated state; the browser holds
   an opaque session cookie.
2. **Token-based authentication** (JWT) — the server is stateless; the client holds a signed
   token and presents it on each request.

JWT is the reflexive default in modern tutorials. It solves a problem this application does
not have: authenticating many independent clients, possibly across services, without shared
server state. It also brings properties that are actively unhelpful here:

- A JWT **cannot be revoked** before expiry without server-side state — which reintroduces the
  session store the token was meant to avoid. "Sign out" and "disable this user" both require
  revocation.
- A JWT must be stored **somewhere** in the browser. `localStorage` is readable by any script
  that achieves XSS, which is a worse exposure than an `HttpOnly` cookie.
- **CSRF does not go away.** A token in `localStorage` sent via `fetch` is still a
  credential-bearing request; it must be protected, typically by putting the token in a
  non-`HttpOnly` cookie — which is a session cookie wearing a disguise.
- Every page render would need token plumbing, and a plain HTML form post cannot carry an
  `Authorization` header at all, so forms would need JavaScript — violating NFR-2.

## Decision

Use **Spring Security with server-side, session-based authentication**:

- Authentication is a form login (`GET /login`, `POST /login`), with logout invalidating the
  session server-side.
- The authenticated principal and its authorities live in the **`HttpSession`**; the browser
  holds an opaque **`JSESSIONID`** cookie.
- Passwords are verified against a **BCrypt** hash.
- **CSRF protection stays enabled** (Spring Security's default). Thymeleaf injects the token
  into every rendered form automatically.
- **Session fixation protection is enabled**: the session identifier is rotated on successful
  authentication.
- Cookie hardening: **`HttpOnly`**, **`SameSite=Lax`**, and **`Secure`** in any environment
  served over HTTPS.
- Authorisation is enforced **server-side on every request** — route rules plus ownership
  checks in the service layer, never a UI-only check.
- An **`AuthenticationSuccessEvent`/`AuthenticationFailureBadCredentialsEvent` listener**
  writes sign-in attempts to the audit trail, including failed attempts.

## Consequences

**Positive**

- **Revocation is immediate and trivial.** Sign-out, disabling a user, or changing a role takes
  effect on the next request, because the server holds the state.
- **No credential is reachable by JavaScript.** An `HttpOnly` cookie is not readable by a
  script that achieves XSS; a `localStorage` token would be.
- **CSRF protection is inherent and automatic.** A cross-site form post carries no valid
  session cookie under `SameSite=Lax`, and the token check rejects it regardless. Thymeleaf
  injects the token so developers cannot forget it.
- **Plain HTML forms work.** No JavaScript is needed for login, upload, or review — satisfying
  NFR-2 and keeping the Selenium suite straightforward.
- **This is the framework's well-trodden path.** Spring Security's session support is its
  oldest and most thoroughly exercised configuration; the security-critical code is the
  library's, not ours.
- **The login page is served by the same application** with the same CSRF machinery, so there
  is no separate auth surface.

**Negative / accepted costs**

- **The application holds session state, so it is stateful.** Horizontal scaling would require
  sticky sessions or a shared session store (e.g. Spring Session with JDBC or Redis). The MVP
  runs a single instance, so this is deferred rather than solved — it is the main cost of this
  decision and is recorded deliberately.
- **Loss of in-memory sessions on restart** signs every user out. Accepted: a restart is a
  deployment, and re-authentication is a minor inconvenience. `SESSION` persistence is not
  worth a store for the MVP.
- **In-memory session storage grows with concurrent users.** Bounded by the course-sized
  user base, and mitigated by session timeouts.
- **Session cookies are a CSRF target by nature.** That is precisely why CSRF protection is
  mandatory here rather than optional, and why it may not be disabled without an ADR
  containing a threat analysis ([`CLAUDE.md` §7](../../CLAUDE.md)).

## Alternatives considered

| Alternative | Why not |
| --- | --- |
| **JWT bearer tokens** | Solves stateless multi-client auth, which this application does not need. Adds non-revocable credentials, a browser storage problem, per-page token plumbing, and JavaScript-dependent forms — while CSRF protection is still required. Net negative on both security and complexity. |
| **HTTP Basic authentication** | No login page, no logout, no session, and credentials resent on every request. Cannot express "session invalidated" and offers a poor browser UX. |
| **Spring Session with Redis** | The correct answer *if* the application needed to scale horizontally. Adds Redis to provision, secure, and health-check via Ansible and Compose for a requirement the MVP does not have. The natural upgrade path if that changes. |
| **OpenID Connect / OAuth2 against an external identity provider** | Shifts identity management to a provider, but adds an external dependency, credentials, and a redirect flow to every environment, and the MVP is explicitly self-contained with local accounts (`mvp.md` §2.1). |
| **Custom authentication (hand-rolled filter and session handling)** | Writing security-critical authentication by hand is how vulnerabilities are introduced. Spring Security's tested implementation is strictly preferable. |

## Follow-up

- An integration test must prove that an unauthenticated request to a protected route is
  rejected, that a `STUDENT` cannot read another student's submission, and that a
  state-changing request without a CSRF token is refused.
- Session timeout and cookie attributes must be configuration values, not hard-coded, so they
  can differ between local and deployed environments.
