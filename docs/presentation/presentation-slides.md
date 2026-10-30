# Presentation Slides: Git-Driven Online Assignment Submission Portal

**Institution:** Department of Computer Engineering
**Course:** DevOps (B.E. Semester VII)
**Candidate:** Ayush Gole (Roll No: 23102A0005)
**Faculty Guide:** Prof. Snehal Andhare
**Artifact:** `docs/presentation/Online_Assignment_Portal_DevOps_Viva.pptx`

---

## Slide 1: Title & Project Overview
* **Title:** Git-Driven Online Assignment Submission Portal
* **Subtitle:** End-to-End DevOps Lifecycle, Continuous Delivery & System Validation
* **Candidate:** Ayush Gole | Roll Number: 23102A0005 | Branch: Computer Engineering
* **Key Topics:** 15-Task Agile Backlog, Multi-Tiered Testing, Docker Multi-Stage Packaging, Jenkins CI/CD Quality Gates, and Ansible Configuration Management.

## Slide 2: Problem Statement & Stakeholder Scope
* **Target Users:** Students submitting coursework, Faculty Reviewers evaluating submissions, System Administrators overseeing audit and access logs.
* **Core Pain Points:** Unvalidated file formats, lost submissions, lack of concurrency control, and untracked status transitions.
* **Approved MVP Scope:** 28 Functional Requirements (FR-1 to FR-28) covering session auth, PDF validation, optimistic locking, and audit trails.
* **Success Criteria:** 100% test pass across all pyramid tiers, automated delivery pipeline, and sub-10 second deployments.

## Slide 3: Application Architecture & Technology Setup
* **Core Stack:** Java 21 LTS, Spring Boot 4.1.1, Thymeleaf, Spring Security, MySQL 8.4 LTS, and Tomcat.
* **Toolchain Enforcement:** Maven Wrapper (mvnw 3.9.16) and `maven-enforcer-plugin` preventing version drift.
* **Security Architecture:** BCrypt password hashing, CSRF tokens, session fixation protection, and server-side role authorization.
* **Document Handling:** SHA-256 integrity checksums and magic-byte PDF content validation.

## Slide 4: Agile Planning & DevOps Lifecycle
* **Product Backlog:** 15 user stories (US-01 to US-15) prioritized by business risk and operational dependency.
* **Sprint Organization:** Sprint 1 (Core MVP, Security & Storage); Sprint 2 (Reviewer Decisions, Jenkins CI/CD, and Ansible Provisioning).
* **Definition of Done (DoD):** Enforcer passing, zero warnings, 100% test success, and peer PR code review approved.
* **Branching Strategy:** Feature branching (`feat/*`), pull request review cards, and merge conflict resolution records.

## Slide 5: Comprehensive Automated Testing Pyramid
* **Unit Tests (Surefire):** 27 tests validating password encoding, user details service, and file format validation (< 4s).
* **Integration Tests (Failsafe):** 103 tests leveraging Testcontainers MySQL 8.4 container to verify JPA persistence and optimistic locking.
* **Selenium 4 E2E Suite:** Page Object Model (POM) driving student uploads and reviewer approval/rejection workflows in headless Chrome.
* **Automated Failure Artifacts:** JUnit 5 `E2EFailureArtifacts` extension automatically saving screenshot and DOM snapshots upon assertion failure.

## Slide 6: Continuous Integration & Quality Gates in Jenkins
* **Declarative Pipeline:** 512-line Jenkinsfile orchestrating build, test, packaging, and deployment stages.
* **Quality Gate Mechanism:** `catchError` blocks coupled with `when { currentBuild.currentResult == 'SUCCESS' }` conditions.
* **Defect Demonstration Drill:** Intentional removal of `multipart/form-data` in `upload.html` halted deployment at the Selenium E2E stage.
* **Defect Correction:** Restoring the attribute resulted in a clean 134/134 test pass and unlocked container packaging and deployment.

## Slide 7: Docker Containerization & Multi-Stage Architecture
* **Multi-Stage Build:** Eclipse Temurin 21 JRE runtime, extracting JAR layers via `jarmode=tools extract --launcher --layers`.
* **Layer Caching:** Isolates dependencies, loader, and snapshot libraries from application classes (content size: 172 MB).
* **Security Controls:** Unprivileged user `portal` (UID 1001), isolated upload volume `/var/lib/portal/uploads`, and read-only app root.
* **Operational Monitoring:** Built-in `HEALTHCHECK` with curl against `/actuator/health` and graceful `STOPSIGNAL SIGTERM` handling.

## Slide 8: Configuration Management & Idempotent Provisioning
* **Infrastructure as Code:** Ansible modular roles (`common`, `docker`, `application`) for host configuration.
* **Secret Management:** Sensitive credentials encrypted using Ansible Vault in group variables.
* **Idempotency Guarantee:** First convergence run applies 14 changes (`changed=14`); second run achieves zero drift (`changed=0, failed=0`).
* **Service Supervision:** Dedicated `assignment-portal.service` systemd unit ensuring automatic restarts and crash recovery.

## Slide 9: Continuous Deployment & Rollback Recovery Drill
* **Versioned Tagging:** Semantic release tag `v1.0.0` baked into image metadata via `APP_VERSION` and published to Docker Hub.
* **Continuous Deployment:** Automated container rollout upon successful completion of all quality gates.
* **Release Ledger:** Tracks deployment history and known-good versions in `deploy/release-ledger.sh`.
* **Rollback Demonstration:** Exercised automated rollback to prior stable version `v1.0.0` upon simulating a failed deployment.

## Slide 10: Conclusion & Academic Deliverables
* **Curriculum Alignment:** 100% of all 15 tasks completed with empirical proof and formal documentation.
* **Comprehensive Evidence:** Raw test reports, Docker logs, Ansible execution records, and high-resolution terminal screenshots.
* **Institutional Reports:** 15 finalized Word documents in `docs/words/`, LaTeX whitepaper, and presentation deck.
