# Task 12: Jenkins-Docker Continuous Deployment

**Evidence status:** CAPTURED

| Field | Details |
| --- | --- |
| Task | 12: Jenkins-Docker Continuous Deployment |
| Objective | Extend the Jenkins CI pipeline into an automated Continuous Deployment (CD) pipeline that builds versioned Docker images, publishes them to Docker Hub, and automatically deploys fresh containers after tests pass. |
| Implementation | Jenkinsfile implements a conditional post-test delivery flow (when { expression { currentBuild.currentResult == 'SUCCESS' } }). The Docker Build stage injects APP_VERSION=1.0.0, tags both versioned (ayushgole/online-assignment-portal:v1.0.0) and latest images, authenticates and publishes to Docker Hub, and executes the Deploy stage with automated health check verification. |
| Primary files | Jenkinsfile, Dockerfile, compose.yaml, deploy/compose.prod.yaml |
| Command/demo | Jenkins pipeline execution; docker tag; docker manifest inspect; docker push |
| Expected result | Versioned image v1.0.0 is created and tagged; registry manifest inspected; end-to-end pipeline passes all tests and deploys the container with health check status UP. |

## Execution Summary and Continuous Deployment Verification

1. **Versioned Image and Registry Tagging:**
   - Multi-stage image built and tagged:
     * `ayushgole/online-assignment-portal:v1.0.0`
     * `ayushgole/online-assignment-portal:latest`
     * `online-assignment-portal:v1.0.0`
   - Image ID: `64815e3ccd8a`
   - Content Size: 172 MB (Virtual: 577 MB)
   - Manifest Digest: `sha256:64815e3ccd8a1ee10bbc9dddf17cdec4975072f4788bff15ccf5f74eb8739a67`
   - Configuration verification: Unprivileged user `portal` (UID 1001), built-in curl health probe, and baked `APP_VERSION=1.0.0`.

2. **Commit-to-Container Pipeline Flow:**
   - Pre-deployment Quality Gates: 27 unit tests, 103 integration tests, and 4 Selenium E2E tests pass (134/134 total).
   - Packaging & Container Build: Triggered automatically upon test suite success.
   - Registry Push: Docker Hub authenticated push for release tag `v1.0.0` and floating tag `latest`.
   - Continuous Deployment: Automatically deploys the fresh container image and verifies `/actuator/health` probe returns HTTP 200 OK (status: `UP`).
   - Pipeline outcome: SUCCESS.

## Evidence Artifacts

1. **Versioned Image Tagging & Registry Manifest Screenshot:**
   `docs/evidence/12-cd/2026-10-20-docker-version-registry-tag.png`
2. **Jenkins Continuous Deployment Pipeline Screenshot:**
   `docs/evidence/12-cd/2026-10-20-jenkins-cd-pipeline-deploy.png`
