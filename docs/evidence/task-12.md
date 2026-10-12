# Task 12 — Docker image and Compose stack

**Evidence status:** PENDING_CAPTURE

| Field | Details |
| --- | --- |
| Task | 12: Docker image and Compose stack |
| Objective | Package the tested executable JAR into a small Java runtime image and define the app/database services with persistent data. |
| Implementation | Dockerfile uses a multi-stage layer extraction, Java 21 JRE, a non-root UID 1001, root-owned application code, and a dedicated writable uploads path. Compose defines MySQL and app with health checks and named volumes. |
| Primary files | [Dockerfile](../../Dockerfile), [compose.yaml](../../compose.yaml), [deploy/compose.prod.yaml](../../deploy/compose.prod.yaml), [.dockerignore](../../.dockerignore), [docs/operations/docker.md](../../docs/operations/docker.md) |
| Command/demo | Windows: .\mvnw.cmd -B clean package -DskipTests; docker compose --env-file .env.example -f compose.yaml config --quiet; docker build -t online-assignment-portal:local . |
| Expected result | Compose configuration validates; image builds from the JAR, starts as a non-root user, reports health, and named database/upload volumes survive app-container replacement. |

## Screenshot / evidence to capture

PENDING_CAPTURE

PENDING_CAPTURE — capture Docker build log, image inspect output proving runtime user/base, Compose health state, and volume mounts. Docker daemon was unavailable in the recent review.

Save real output under docs/evidence/ using the UTC date, short commit SHA, and a concise description. Record the actual command, result, environment, and revision. Redact credentials, tokens, session identifiers, and personal data. Do not edit logs or screenshots to imply a result that did not occur.

