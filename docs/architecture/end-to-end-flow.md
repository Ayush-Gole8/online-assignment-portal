# End-to-End Flow

**Related:** [System Overview](system-overview.md) | [Application Structure](application-structure.md)
| [Security](security.md) | [Data Model](data-model.md)

This document traces every layer a request or a release event passes through, from the browser
(or CI agent) to the database and back. It complements the sequence diagrams in
`system-overview.md` with a prose explanation suitable for a viva walkthrough.

---

## 1. Browser request lifecycle -- authenticated upload

A student submits an assignment. This is the most demanding path in the system because it
touches every component.

```
STUDENT browser
    |
    | POST /submissions  (multipart form, session cookie, CSRF token)
    v
Tomcat servlet container
    |-- CSRF filter:        validates the _csrf token in the form body
    |-- Session filter:     restores the SecurityContext from the JSESSIONID cookie
    |-- Authorization filter: checks hasRole('STUDENT') -- rejects with 403 if absent
    |
    v
SubmissionController.upload(MultipartFile, ...)
    |
    +-- FileValidationService.validate(file)
    |       checks: extension is .pdf
    |               content type is application/pdf (from the Part)
    |               size <= 10 MB
    |               first 4 bytes are the PDF magic bytes (%PDF)
    |       returns: valid, or a ValidationException with a field message
    |
    +-- ChecksumService.sha256(bytes)
    |       reads the upload bytes ONCE (they are buffered)
    |       returns: hex-encoded SHA-256 digest
    |
    +-- StorageService.store(bytes, storageId)
    |       generates a StorageIdentifier (UUID-based, no user input reaches the path)
    |       resolves the path under STORAGE_ROOT
    |       writes bytes atomically (temp file + move)
    |       returns: the StorageIdentifier
    |
    +-- SubmissionService.submit(owner, title, originalFilename, storageId, checksum)
            @Transactional
            creates Submission (status=SUBMITTED, submittedAt=now, version=0)
            creates SubmissionStatusHistory row (CREATED->SUBMITTED, actor=student)
            saves both in one transaction
            if the DB write fails: the file is already stored; orphan reconciliation
            is a documented manual step (the reverse would break downloads)
    |
    v
302 redirect to GET /submissions (student's submission list)
```

---

## 2. Reviewer decision lifecycle

```
REVIEWER browser
    |
    | POST /review/submissions/{id}  (form, CSRF token, decision + comment)
    v
Tomcat
    |-- CSRF, session, authorization (hasRole('REVIEWER') or hasRole('ADMIN'))
    v
ReviewController.decide(id, ReviewDecision)
    |
    +-- ReviewService.recordDecision(id, decision)
    |       validates decision policy:
    |         REJECTED requires a non-empty comment
    |         APPROVED must not carry a comment
    |         decision must be APPROVED or REJECTED
    |
    +-- SubmissionService.recordDecision(id, decision, comment, reviewer)
            @Transactional
            loads Submission by id (for update, with @Version for optimistic locking)
            checks Submission is in SUBMITTED status (else: SubmissionAlreadyDecidedException)
            calls submission.approve() or submission.reject()
              -- these are entity methods that enforce the terminal state rule
              -- they set status, reviewerId, reviewedAt, reviewerComment
            creates SubmissionStatusHistory row (SUBMITTED->APPROVED/REJECTED, actor=reviewer)
            saves (JPA flush; @Version increment)
            if two reviewers race: the loser gets OptimisticLockingFailureException
              -- mapped to a user-visible "decision not recorded" message
    |
    v
302 redirect to review queue
```

---

## 3. File download lifecycle

A student requests their own file. A reviewer requests a submission's file.

```
STUDENT: GET /submissions/{id}/file
REVIEWER: GET /review/submissions/{id}/file

    v
Authorization:
    STUDENT path: SubmissionService.findByIdForOwner(id, currentUser)
        -- WHERE id=? AND owner_id=? (the database never loads another user's row)
        -- returns 404 if not found OR not owned
    REVIEWER path: SubmissionService.findById(id)
        -- no ownership restriction; reviewer sees all

    v
StorageService.load(storageIdentifier)
    -- resolves path under STORAGE_ROOT
    -- returns a Resource

    v
Controller sets response headers:
    Content-Disposition: attachment; filename="<display name>"
    Content-Type: application/pdf
    X-Content-Type-Options: nosniff
    -- file is never inline-rendered

    v
Tomcat streams bytes to the browser
```

---

## 4. Pipeline event lifecycle -- a commit on a feature branch

```
Developer pushes a commit to feature/my-change
    |
    v
GitHub notifies Jenkins (webhook or polling)
    |
    v
Jenkins Declarative pipeline (Jenkinsfile)
    |
    +-- Checkout          git fetch + checkout the exact SHA
    +-- Validate          print java/git/docker/compose versions; fail on missing tool
    +-- Build             ./mvnw.cmd -B clean test-compile
    +-- Unit Tests        ./mvnw.cmd -B test
    |                       Surefire runs *Test classes (tagged 'unit')
    |                       excludes 'integration' and 'e2e' groups
    +-- Integration Tests ./mvnw.cmd -B failsafe:integration-test failsafe:verify
    |                       Failsafe runs *IT classes (tagged 'integration')
    |                       Testcontainers starts mysql:8.4, runs migrations, tests, stops
    |                       excludes 'e2e' group
    +-- Package           ./mvnw.cmd -B package -DskipTests
    |                       produces target/online-assignment-portal-0.0.1-SNAPSHOT.jar
    +-- Start Test Env    scripts\ci\start-database.cmd
    |                       scripts\ci\start-application.cmd
    |                       scripts\ci\wait-for-health.cmd
    +-- Selenium E2E      scripts\ci\run-e2e.cmd
    |                       ./mvnw failsafe:integration-test@e2e-tests
    |                       Failsafe runs *E2E classes (tagged 'e2e')
    +-- Publish Reports   junit, archiveArtifacts (reports + screenshots)
    +-- Release Version   scripts\ops\release-version.cmd
    |                       no vX.Y.Z tag -> IS_RELEASE=false
    +-- Docker Build      docker build --build-arg APP_VERSION=<commit> ...
    |                       runs even on feature branches (proves Dockerfile still builds)
    +-- Publish Image     SKIPPED (IS_RELEASE=false)
    +-- Deploy            SKIPPED (IS_RELEASE=false)
    |
    v
post { always }
    stop-environment.cmd, collect-diagnostics.cmd
    junit, archiveArtifacts (JAR, diagnostics, reports)
    cleanWs()
```

---

## 5. Pipeline event lifecycle -- a vX.Y.Z release tag

```
Developer pushes tag v1.0.0
    |
    v
Jenkins runs the same CI stages as above, then:
    |
    +-- Release Version   scripts\ops\release-version.cmd
    |                       IS_RELEASE=true, IMAGE_TAG=1.0.0
    +-- Docker Build      docker build --build-arg APP_VERSION=1.0.0 -t portal:1.0.0 .
    +-- Publish Image     scripts\ops\docker-login.cmd  (token piped, not logged)
    |                       scripts\ops\publish-image.cmd
    |                         tags local image as <IMAGE_REPOSITORY>:1.0.0
    |                         docker push <IMAGE_REPOSITORY>:1.0.0
    |                       docker logout (always, in stage post)
    +-- Deploy            (if DEPLOY_ENV != 'none')
            sshagent(['deploy-ssh-key'])
                scripts\ops\deploy-release.cmd
                    scp deploy scripts to host
                    ssh: deploy/pre-release.sh
                        check volumes exist
                        mysqldump to backup
                        write releases/1.0.0.env
                    ssh: deploy/deploy-on-host.sh
                        rewrite .env (APP_IMAGE_TAG=1.0.0)
                        docker compose pull
                        docker compose up --no-build --wait
                        wait for /actuator/health
                    scripts\ops\smoke-test.cmd
                        GET /actuator/health  -- must be UP
                        GET /actuator/info    -- version must be "1.0.0"
                        GET /submissions      -- must redirect to /login (no open routes)
                    On success: deploy/release-ledger.sh append success
                    On failure: scripts\ops\rollback-release.cmd
                        ssh: deploy/rollback-on-host.sh
                            read ledger for last stable version
                            rewrite .env
                            docker compose up ...
                            wait for health
                            smoke test
                            deploy/release-ledger.sh append rollback
```

---

## 6. Ansible provisioning lifecycle (first run)

```
Operator runs:
    ansible-playbook -i ansible/inventory/staging.ini ansible/site.yml --ask-vault-pass
    |
    v
Role: common
    apt update, install curl git jq
    install chrony, enable NTP sync
    |
    v
Role: docker
    install Docker Engine (apt keyring, repository, package)
    install docker-compose-plugin
    install python3-docker (for Ansible's docker modules)
    write /etc/docker/daemon.json:
        { "log-driver": "json-file",
          "log-opts": { "max-size": "10m", "max-file": "3" },
          "live-restore": true }
    systemctl enable --now docker
    |
    v
Role: application
    create /opt/assignment-portal/
    copy deploy/compose.prod.yaml
    render .env from vault variables (db password, image repository, etc.)
    docker compose pull (fetches the image)
    create named volumes (app-uploads, mysql-data)
    write systemd unit /etc/systemd/system/assignment-portal.service
    systemctl enable --now assignment-portal
    wait for /actuator/health == UP
    assert /actuator/info version == expected version

Second run: all tasks report ok or skipped -- changed=0
```

---

## 7. Security boundary summary

| Boundary | Enforced by |
| --- | --- |
| Unauthenticated requests | Spring Security filter chain -- redirects to /login |
| CSRF on state-changing forms | Spring Security CSRF filter -- 403 on missing/wrong token |
| Role checks | `hasRole(...)` in SecurityConfiguration + ownership WHERE clauses in repositories |
| Upload content | FileValidationService -- extension, content type, size, magic bytes |
| Path traversal | StorageIdentifier -- only UUID-derived paths reach the filesystem |
| Secret leakage | No secrets in committed files; environment variables only |
| Stack traces to clients | GlobalExceptionHandler + error.html -- user-safe messages only |
| Template commentary | Thymeleaf parser-level comments only -- no HTML comments reach browser |
