# Task 12 -- Docker image and Compose stack

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 12: Docker image and Compose stack |
| Objective | Package the tested executable JAR into a small Java runtime image and define the app/database services with persistent data. |
| Implementation | Dockerfile uses a two-stage build: stage 1 (unpack) extracts the Spring Boot JAR into four dependency-ordered layers; stage 2 (runtime) uses Eclipse Temurin 21 JRE, installs curl for HEALTHCHECK, creates non-root user portal (UID/GID 1001), copies the four layers, declares EXPOSE 8080, and sets STOPSIGNAL SIGTERM for graceful shutdown. compose.yaml defines mysql:8.4 and the app service with health checks and named volumes (app-uploads, mysql-data). |
| Primary files | Dockerfile, compose.yaml, deploy/compose.prod.yaml, .dockerignore, docs/operations/docker.md |
| Command/demo | Windows: .\mvnw.cmd -B clean package -DskipTests; docker compose --env-file .env.example -f compose.yaml config --quiet; docker build -t online-assignment-portal:local . |
| Expected result | Compose configuration validates; image builds from the JAR; container starts as non-root user portal; HEALTHCHECK reports healthy; named database and upload volumes survive app-container replacement. |

## Screenshot / evidence to capture

PENDING_CAPTURE -- Capture Docker build log, image inspect output (runtime user, base image, layers), Compose health state, and volume mounts. Docker daemon was unavailable in the recent review.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise
description. Record the actual command, result, environment, and revision. Redact credentials,
tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a
result that did not occur.
