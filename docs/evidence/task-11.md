# Task 11: Docker Image and Container Lifecycle

**Evidence status:** CAPTURED

| Field | Details |
| --- | --- |
| Task | 11: Docker Image and Container Lifecycle |
| Objective | Build a multi-stage Docker image, package the Spring Boot portal, inspect image layers and health checks, and demonstrate the complete container lifecycle. |
| Implementation | Multi-stage Dockerfile utilizes Eclipse Temurin 21 JRE. Stage 1 unpacks the executable JAR into 4 distinct layers (dependencies, spring-boot-loader, snapshot-dependencies, application) using jarmode=tools extract --launcher --layers. Stage 2 executes as an unprivileged user 'portal' (UID 1001), declares HEALTHCHECK via curl, configures JVM ergonomics (-XX:MaxRAMPercentage=75.0), and enforces STOPSIGNAL SIGTERM for graceful connection draining. |
| Primary files | Dockerfile, compose.yaml, deploy/compose.prod.yaml, src/main/resources/application.yml |
| Command/demo | docker build -t online-assignment-portal:v1.0.0 .; docker run -d --name assignment-portal-container -p 8082:8080 ...; docker ps; docker logs; docker stop; docker start; docker rm -f |
| Expected result | Image compiles with 172 MB content size; container starts and achieves healthy status; /actuator/health returns UP; logs show Tomcat starting on port 8080 in 7.644 s; stop exits with code 143 (SIGTERM); restart resumes healthy service. |

## Execution Summary and Lifecycle Verification

1. **Docker Multi-Stage Build:**
   - Command: `docker build -t online-assignment-portal:v1.0.0 .`
   - Image Details: Tag `v1.0.0`, Image ID `64815e3ccd8a`, Content Size 172 MB (Virtual 577 MB).
   - Layer caching: Base image and toolchain layers cached; application layer builds in 3.0 seconds.

2. **Container Launch & Port Mapping:**
   - Command: `docker run -d --name assignment-portal-container -p 8082:8080 -e SPRING_PROFILES_ACTIVE=dev -e APP_STORAGE_ROOT=/var/lib/portal/uploads -e SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/assignment_portal online-assignment-portal:v1.0.0`
   - Container ID: `3940a6c81047`
   - Status: `Up 24 seconds (healthy)`
   - Healthcheck: Automated probe curl against `/actuator/health` passed; returned `{"groups":["liveness","readiness"],"status":"UP"}`.

3. **Lifecycle Operations:**
   - **Log Inspection:** `docker logs` confirmed Flyway schema validation, database pool initialization, unprivileged user `portal` execution, and Tomcat starting on port 8080 in 7.644 s.
   - **Graceful Shutdown:** `docker stop` dispatched STOPSIGNAL SIGTERM; Tomcat drained requests cleanly, exiting with status code 143 (128 + 15).
   - **Container Restart:** `docker start` resumed the container, immediately regaining `healthy` status.
   - **Resource Removal:** `docker rm -f` cleanly unmounted and destroyed ephemeral container layers without impacting persistent volumes.

## Evidence Artifacts

1. **Docker Build & Container Run Screenshot:**
   `docs/evidence/11-docker/2026-10-20-docker-build-and-run.png`
2. **Container Lifecycle Drill Screenshot (Logs, Stop, Restart, Remove):**
   `docs/evidence/11-docker/2026-10-20-docker-container-lifecycle.png`
