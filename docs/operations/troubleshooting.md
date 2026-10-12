# Troubleshooting

**Related:** [Toolchain](toolchain.md) | [Docker](docker.md) | [Jenkins CI](jenkins-ci.md)
| [Ansible](ansible.md) | [Demo Runbook](demo-runbook.md)

This document records known failure modes, their diagnosis, and their remediation. Entries are
added when a failure is encountered and confirmed. Do not add speculative entries.

---

## 1. Build failures

### 1.1 Wrong Java version

**Symptom:**
```
[ERROR] Rule 0: org.apache.maven.enforcer.rules.RequireJavaVersion failed with message:
Requires a JDK 21 to build, but Maven is running on a different JDK.
```

**Cause:** `JAVA_HOME` points to a JDK that is not version 21, or the JDK on `PATH` is not 21.

**Fix:**
```powershell
# Verify which Java Maven is using
.\mvnw.cmd -version
# Set JAVA_HOME to a JDK 21 root (not its bin directory)
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21..."
.\mvnw.cmd -version   # should now show Java version 21
```

---

### 1.2 Integration tests fail: "Could not find a valid Docker environment"

**Symptom:**
```
java.lang.IllegalStateException: Could not find a valid Docker environment.
    at org.testcontainers.containers.GenericContainer.start(...)
```

**Cause:** Docker daemon is not running when `./mvnw clean verify` is invoked.

**Fix:** Start Docker Desktop (Windows) or `systemctl start docker` (Linux). Confirm with
`docker version`.

**Note:** This was the exact failure in the 2026-10-02 Run 1. Run 2 succeeded once Docker
was available.

---

### 1.3 Testcontainers: "NoClassDefFoundError: Could not initialize class MySqlIntegrationTest"

**Symptom:** 100+ integration test errors all reading `NoClassDefFoundError` after a single
`IllegalStateException` in `MySqlIntegrationTest.<clinit>`.

**Cause:** `MySqlIntegrationTest` starts its shared MySQL container in a static initialiser.
When the initialiser fails (e.g. no Docker), the class is poisoned for the JVM and every test
class that extends it fails with `NoClassDefFoundError`.

**Fix:** Same as 1.2 -- start Docker first. The static initialiser runs once per JVM; a
successful Docker connection prevents the cascade.

---

### 1.4 Flyway: "Unsupported Database: MySQL"

**Symptom:**
```
org.flywaydb.core.api.exception.FlywayException: Unsupported Database: MySQL
```

**Cause:** `flyway-mysql` module is missing from the classpath. Flyway 10+ split per-database
support into separate modules.

**Fix:** Verify `flyway-mysql` is declared in `pom.xml`. It should be present alongside
`flyway-core` and `spring-boot-starter-flyway`.

---

### 1.5 Hibernate validate fails: "Schema-validation: missing column"

**Symptom:** Application fails to start with a Hibernate schema validation error.

**Cause:** A Flyway migration was not applied (wrong `flyway.locations`, missing migration file,
or Flyway disabled).

**Fix:** Check `spring.flyway.enabled=true` in `application.yml`. Verify `db/migration/`
contains all `V*.sql` files. Run with `logging.level.org.flywaydb=DEBUG` to see which
migrations applied.

---

## 2. Application startup failures

### 2.1 "DB_PASSWORD is not set" / Compose aborts

**Symptom:**
```
error: DB_PASSWORD is not set. Copy .env.example to .env and fill it in.
```

**Cause:** `compose.yaml` uses `:?` syntax for required variables. The `.env` file is missing
or the variable is empty.

**Fix:**
```bash
cp .env.example .env
# Edit .env and set DB_PASSWORD, DB_ROOT_PASSWORD, and the seed passwords
```

---

### 2.2 Application starts but shows "Error creating bean"

**Symptom:** Spring context fails to load; stack trace mentions a missing bean.

**Cause (most likely):** Missing `spring-boot-starter-flyway` or `spring-boot-starter-security`
starter on Spring Boot 4, where the starters supply autoconfiguration that the plain library
does not.

**Fix:** Confirm the appropriate starter is declared in `pom.xml`, not just the core library.
See `pom.xml` comments for the exact rationale.

---

### 2.3 "Table 'users' doesn't exist"

**Symptom:** The application starts but the first database query fails with a missing table.

**Cause:** Flyway ran no migrations (no autoconfiguration, wrong location, or `spring.flyway.enabled=false`).

**Fix:** Same as 1.5. Also check `spring.flyway.locations` in `application.yml` matches the
actual path `db/migration/`.

---

## 3. Selenium E2E failures

### 3.1 Connection refused to localhost:8080

**Symptom:** Every E2E test fails immediately with `WebDriverException: connection refused`.

**Cause:** The application was not started before the E2E suite ran.

**Fix:** Run the full start sequence:
```bash
docker compose up -d db
./mvnw clean package -DskipTests
scripts\ci\start-application.cmd
scripts\ci\wait-for-health.cmd
./mvnw verify -Pe2e
```

---

### 3.2 Selenium Manager cannot download ChromeDriver

**Symptom:** Test hangs or fails with a network error during driver download.

**Cause:** The CI agent has no internet access to `chromedriver.storage.googleapis.com`.

**Fix:** Pre-install ChromeDriver matching the installed Chrome version. Set the
`webdriver.chrome.driver` system property in `run-e2e.cmd`. Or provide a local mirror.

---

## 4. Docker and container failures

### 4.1 Docker HEALTHCHECK reports unhealthy

**Symptom:** `docker compose ps` shows `app` as `unhealthy` even though the process is running.

**Diagnosis:**
```bash
docker compose logs app | tail -50        # application log
docker inspect assignment-portal-app      # health check details
curl http://localhost:8080/actuator/health # direct probe
```

**Common causes:**
- Database not ready: MySQL is slow to initialise on first run. The `start-period=60s` should
  cover normal startup. Increase it if the host is slow.
- Flyway migration failure: check `docker compose logs app` for Flyway errors.
- Wrong `APP_PORT`: if `APP_PORT` was overridden, the HEALTHCHECK uses `${APP_PORT:-8080}` --
  verify the variable is consistent.

---

### 4.2 Uploads volume not writable

**Symptom:** File upload succeeds for the API but the file is missing on disk; or a
`FileSystemException: Permission denied` in the application log.

**Cause:** The `app-uploads` volume is mounted but the `portal` user (UID 1001) cannot write
to it.

**Fix:** The `Dockerfile` creates `/var/lib/portal/uploads` owned by `portal:portal`. If the
volume was created by a different process (e.g. `root`), the ownership may be wrong.
```bash
docker compose exec app ls -la /var/lib/portal/uploads
# If owned by root: recreate the volume
docker compose down
docker volume rm online-assignment-portal_app-uploads
docker compose up -d
```

---

## 5. Jenkins pipeline failures

### 5.1 "Host key verification failed" during Deploy

**Symptom:** `scripts\ops\deploy-release.cmd` fails with `Host key verification failed`.

**Cause:** The deployment host's key is not in the Jenkins agent's `known_hosts`.

**Fix:** On the Jenkins agent:
```bash
ssh-keyscan -H <deploy-host> >> ~/.ssh/known_hosts
```
Do not disable `StrictHostKeyChecking` -- the verification is intentional security, not an
obstacle.

---

### 5.2 "IMAGE_REPOSITORY is not set"

**Symptom:** The Publish Image stage fails immediately.

**Cause:** The `IMAGE_REPOSITORY` build parameter was not set when triggering the release build.

**Fix:** Set `IMAGE_REPOSITORY` to `<dockerhub-account>/<repo-name>` in the Jenkins build
parameters before triggering. The empty default is intentional -- a guessed account pushes to
the wrong repository.

---

### 5.3 Docker login credential not found

**Symptom:** `scripts\ops\docker-login.cmd` fails with a credential lookup error.

**Cause:** The Jenkins credentials store does not contain an entry with ID
`dockerhub-credentials`.

**Fix:** Add a "Username with password" credential in Jenkins with ID `dockerhub-credentials`.
The username is the Docker Hub account name; the password is an access token (not the account
password).

---

## 6. Ansible failures

### 6.1 "Vault secret not found" or decryption error

**Symptom:** Ansible reports a vault decryption error when running the playbook.

**Cause:** Either the vault password is wrong, or `group_vars/vault.yml` was not encrypted with
the expected key.

**Fix:** Confirm the vault password (it is never stored in the repository). Re-encrypt if the
file was accidentally committed unencrypted:
```bash
ansible-vault encrypt ansible/group_vars/vault.yml
```

---

### 6.2 Playbook hangs at "Wait for health check"

**Symptom:** The `application` role hangs at the health-check task.

**Cause:** The application container is not starting, typically because the `.env` file has
wrong database credentials or `IMAGE_TAG` refers to an image that does not exist in the registry.

**Diagnosis on the host:**
```bash
systemctl status assignment-portal
docker compose -f /opt/assignment-portal/compose.prod.yaml logs app
```

---

## 7. Known defects fixed

| Date | Test | Defect | Fix |
| --- | --- | --- | --- |
| 2026-10-02 | `SubmissionUploadIT.returnsTheEnteredMetadataToTheFormAfterARefusal` | Fragment parameter `title` shadowed model attribute `${title}` | Renamed fragment params to `pageTitle`/`pageContent` |
| 2026-10-02 | `UiRenderingIT.rendersTheErrorPageWithoutExposingInternals` | No ModelAndView found for bare `GET /error` with no Accept header | Test now sets `Accept: text/html` and `ERROR_STATUS_CODE` |
| 2026-10-02 | `UiRenderingIT.rendersTheErrorPageWithoutExposingInternals` | HTML comments (naming `BasicErrorController`, `SecurityConfiguration`) were served verbatim to browsers | All 51 comments across 18 templates converted to Thymeleaf parser-level comments |
