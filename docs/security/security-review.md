# Security Review

**Reviewed:** 2026-10-12  
**Scope:** application source and configuration, Maven build, container definitions,
deployment configuration, and Ansible roles. This is a repository review; it does not
replace a deployment-specific threat assessment or an executed penetration test.

## Findings

| Priority | Finding | Status / action |
| --- | --- | --- |
| High | The production Compose file defaults to publishing the application on `0.0.0.0:8080`, while `SESSION_COOKIE_SECURE` defaults to `false`. The Ansible staging defaults also leave the secure cookie flag off, and no TLS terminator is provisioned here. Credentials and session cookies can therefore travel over cleartext HTTP if this port is reachable from an untrusted network. | **Open deployment requirement.** Restrict the port at the network boundary or bind it to loopback behind a TLS terminator; enable `SESSION_COOKIE_SECURE=true` when HTTPS is in place. Verify the effective host firewall, proxy, and cookie settings before exposing a deployment. The repository documents this limitation in `docker-cd.md` and `ansible.md`; it is not safely resolvable without knowing the deployment topology. |
| Medium | The container runtime image's application files were owned by the `portal` process, allowing that process to modify its classes and libraries in `/app`. | **Fixed in this review.** Runtime layers are now root-owned and read-only to the process; `/var/lib/portal/uploads` remains the application-owned writable data directory. |
| Low | Image references use movable tags (`eclipse-temurin:21-jre`, `mysql:8.4`) and the Ansible collection requirement is a lower bound rather than an exact tested version. Rebuilding later may resolve different bits. | **Open reproducibility limitation.** Pin image digests and the exact Ansible collection version after recording the versions validated in CI/deployment. Do not claim a fully reproducible container build while tags remain mutable. |

## Controls verified by source inspection

- **Secrets:** the local `.env` is ignored and is not tracked; `.env.example` contains placeholders. Production values are rendered from Ansible variables into a mode `0640` environment file by a `no_log` task, with secret values intended to come from Ansible Vault. `ansible/group_vars/vault.yml` currently contains placeholders, not encrypted values; replace and encrypt it before adding real credentials. Test credentials in test sources are synthetic fixtures, not deployment credentials.
- **Authentication and authorization:** the single Spring Security filter chain requires authentication by default, scopes student routes to `STUDENT`, and restricts review routes to `REVIEWER` or `ADMIN`. Student reads include ownership in repository queries. CSRF remains enabled with defaults; logout is POST-protected. Session cookies are HttpOnly and SameSite=Lax, with a configurable Secure flag.
- **Uploads and storage:** upload size is bounded at the multipart parser and application validator. Validation checks the PDF signature and extension; the client filename is normalized for display only. A server-generated UUID storage identifier is used for paths, and normalized paths are checked to stay under the configured root. The deployed upload directory is a named persistent volume, separate from MySQL metadata and static web resources. Downloads are attachments with a fixed validated media type.
- **Actuator and errors:** only `health` and `info` are exposed; health details are hidden. The public info payload is limited to build version and environment identifiers. Stack traces and error messages are disabled in server responses, and the shared error handler returns generic pages/statuses.
- **Runtime/container:** the Dockerfile uses a Java 21 JRE stage, runs as UID 1001, and contains no Maven or source tree. Uploaded files are mounted separately. The application does not need to write to its application classes; this review changed their ownership accordingly.
- **Infrastructure:** Ansible uses desired-state modules for Docker resources, has handlers for daemon and application restarts, marks secret-bearing tasks `no_log`, and keeps SSH host key checking enabled. The vault file is deliberately placeholder-only until an operator encrypts real values.

## Review boundary

No deployment host, firewall, TLS endpoint, registry, or Ansible Vault passphrase was available to inspect. No live service or external dependency was probed. The open cleartext-transport finding must be resolved and verified for the actual deployment before it is internet-facing.
