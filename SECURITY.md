# Security Policy

This application accepts **file uploads from authenticated students** and is provisioned onto
a server by an automated pipeline. Both facts shape the security model below.

---

## 1. Reporting a vulnerability

**Do not open a public GitHub issue for a security problem.**

Report privately to the maintainer:

- Email: `ayushgole1910@gmail.com` 
- Or use GitHub's private *Report a vulnerability* advisory form on this repository.

Include: affected component, reproduction steps, impact, and any suggested mitigation. You
will receive an acknowledgement within 5 working days. Please allow 90 days before public
disclosure, or sooner if a fix ships earlier.

## 2. Supported versions

Only the tip of `main` and the currently deployed release are supported. There are no
maintained back-branches.

| Version | Supported |
| --- | --- |
| `main` (unreleased) | Yes |
| Latest deployed release | Yes |
| Older releases | No |

## 3. Threat model

| Asset | Threat | Control |
| --- | --- | --- |
| Uploaded PDFs | Malicious or malformed file reaching disk | Server-side validation: content type, extension allow-list, 10 MB cap, magic-byte check |
| Uploaded PDFs | Path traversal via crafted filename | Original filename is **never** used as a path; a server-generated storage identifier is used |
| Uploaded PDFs | Silent corruption or tampering at rest | SHA-256 checksum recorded per artefact |
| Uploaded PDFs | Malware distribution to reviewers | Files are served with `Content-Disposition: attachment` and `application/pdf` |
| Session | Session hijacking, fixation | Spring Security session auth; session ID rotated on login; `HttpOnly`, `Secure` (in production), `SameSite=Lax` cookies |
| State-changing requests | Cross-site request forgery | CSRF protection **enabled and never disabled**; Thymeleaf injects the token automatically |
| Data | Unauthorised access to another student's submission | Authorisation checked in the service layer, not only in the UI; ownership verified on every read |
| Credentials | Leakage of DB/registry/deploy secrets | No secret in the repository; environment variables or a managed secret store only |
| Audit trail | Repudiation of an approve/reject decision | Every status transition written to `SubmissionStatusHistory` with actor, timestamp, and comment |

## 4. Non-negotiable rules

These are restated from [`CLAUDE.md`](CLAUDE.md) because a security review will check them:

1. **Never** hard-code a password, token, private key, database password, Docker Registry
   credential, or deployment credential anywhere in the repository — including tests, CI
   files, Ansible variables, Compose files, and documentation examples.
2. Configuration is **externalised**. Secrets arrive through environment variables or a
   managed secret store, injected at deploy time.
3. **CSRF protection stays enabled.** Disabling it requires an ADR with a threat analysis.
4. The **original upload filename is never trusted as a filesystem path.** Every upload gets
   a server-generated storage identifier.
5. Uploads are **PDF only, maximum 10 MB**, validated on the **server**. Client-side
   validation is a convenience, never a control.
6. Uploaded files are stored **outside the deployable artefact**, on a persistent volume.
7. Authorisation is enforced **server-side per request**, based on the authenticated
   principal and its role — never on a value supplied by the client.
8. Errors shown to users never leak stack traces, SQL, filesystem paths, or configuration.

## 5. Secret handling

| Context | How the secret is supplied |
| --- | --- |
| Local development | `.env` file, git-ignored; `.env.example` documents the variable names only |
| CI (Jenkins) | Jenkins credentials store, injected as environment variables |
| Deployment | Ansible Vault, or the target host's environment; never a plaintext group_var |
| Container | Environment variables at `docker run` / Compose time |

If a secret is ever committed, treat it as **compromised**: rotate it immediately, then
purge it from history. Deleting the file in a later commit is not sufficient.

## 6. Dependency and image hygiene

- Dependencies are pinned; version ranges are not used.
- Base images are pinned to a specific tag, not `latest`.
- Container images run as a **non-root** user.
- Dependency and image vulnerability scanning is a planned pipeline stage; findings are
  triaged before release.
