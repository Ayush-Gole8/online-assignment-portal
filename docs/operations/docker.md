# Docker — the production image and the container lifecycle

**Related:** [ADR-002](../adr/ADR-002-spring-boot-embedded-tomcat.md) ·
[ADR-004](../adr/ADR-004-filesystem-volume-and-db-metadata.md) ·
[ADR-006](../adr/ADR-006-docker-compose-composition.md) · [Jenkins CI](jenkins-ci.md) ·
[Docker CD](docker-cd.md) · [Toolchain](toolchain.md) · [`Dockerfile`](../../Dockerfile) ·
[`compose.yaml`](../../compose.yaml)

---

## 1. What this document covers

The deliverable is one executable JAR, packaged into one image, run as one container beside one
database. This document describes the image, the composition that runs it, and the container
lifecycle an operator drives by hand.

It does **not** describe a deployment. Publishing the image to a registry, reaching a host,
putting a versioned release on it, verifying it and rolling it back are a separate process with a
separate document: [`docker-cd.md`](docker-cd.md). The boundary is the one this file already
draws — `compose.yaml` here builds from the working tree, for a developer; `deploy/compose.prod.yaml`
there pulls a published version, for a host. **Neither has been executed**, including everything
in the delivery process; both documents carry their own unverified-claims tables (§8 here,
`docker-cd.md` §8 there).

## 2. The image

### 2.1 The base image — tag, and why the digest is not pinned here

| | |
| --- | --- |
| **Image** | `eclipse-temurin:21-jre` |
| **Vendor** | Eclipse Adoptium — the maintained, freely redistributable OpenJDK build |
| **Version** | Java 21 (LTS) — the same major version `maven-enforcer-plugin` requires (`requireJavaVersion [21,22)`) and the same one the test suites ran under |
| **Variant** | The JRE, not the JDK. The runtime executes bytecode; it never compiles it |
| **Digest** | **Not pinned. Recorded as an open gap — §8.** |

Several decisions are visible in that one string:

- **`latest` is not used**, and neither is a bare `eclipse-temurin`. An image tag that moves means
  the same Dockerfile builds something different next month, which makes a rebuild useless as
  evidence and a rollback a guess.
- **The exact patch release is not written either** (`21.0.12_7-jre`, say). A superseded patch tag
  can no longer be pulled, so a stale pin fails the build — and a build that fails is not the same
  thing as a build that repeats. The digest below is the mechanism for pinning; the patch number is
  not.
- **The tag is a build argument**, so pinning — or pointing at a mirrored registry — is a flag
  rather than an edit to the Dockerfile:

  ```bash
  docker build --build-arg JAVA_BASE_IMAGE=eclipse-temurin:21-jre@sha256:<digest> .
  ```

#### Pinning to a digest

`docker buildx imagetools inspect eclipse-temurin:21-jre` prints the digest the tag currently
resolves to. Appending it after `@` freezes the base image against the tag being re-pointed
upstream, which is the only way the *same* tag is guaranteed to keep producing the *same* image.

The procedure, and why it is a procedure rather than a value:

```bash
# 1. Resolve the tag to its digest, from a machine with a route to the registry.
docker buildx imagetools inspect eclipse-temurin:21-jre

# 2. Build with it pinned, and confirm the build still succeeds.
docker build \
  --build-arg JAVA_BASE_IMAGE=eclipse-temurin:21-jre@sha256:<digest> \
  -t online-assignment-portal:local .

# 3. Record the digest and the date on this page (§2.1, the Digest row) and in the
#    Docker Engine section of toolchain.md.
```

**The digest is deliberately left unpinned rather than filled in with a guess.** A wrong digest is
not a weaker pin; it is a build that fails with `manifest unknown`, and it would have been written
here by someone who never resolved the tag. This environment had no route to a registry, so the one
rule that applies is that a claim in this repository is either observed or marked absent
([evidence rules](../evidence/README.md)).

A tag-only pin is still strictly better than `latest`: it fixes the major version and the vendor,
so the image cannot change from Java 21 to Java 22 under a rebuild. What it does not fix is the
patch release. That is the residual gap recorded in §8.

### 2.2 What the image contains, and what it does not

| Requirement | How it is met |
| --- | --- |
| **Multi-stage build** | Two stages. `unpack` explodes the executable JAR into Spring Boot's four layers; the final stage copies only those layers. See §2.3 |
| **Java 21 runtime** | `eclipse-temurin:21-jre` in both stages |
| **Non-root runtime user** | A dedicated `portal` account (uid/gid 1001) is created, every `COPY` is `--chown=portal:portal`, and `USER portal` is set before the entry point. Nothing after it runs as root |
| **No secrets in the Dockerfile** | No credential appears in the file. Everything arrives as an environment variable at run time — §7 |
| **No source files copied into the runtime image** | `.dockerignore` admits exactly one file into the build context: the executable JAR. No `src/`, no `pom.xml`, no migration scripts, no `.git`, no `.env` |
| **No development tools** | No compiler, no Maven, no JDK, no package manager cache. The JRE is the whole toolchain |
| **Explicit exposed port** | `EXPOSE 8080` — the port `server.port: ${APP_PORT:8080}` binds |
| **Reproducible startup command** | `ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]` — exec form, no shell, no expansion, no arguments assembled at run time |
| **Graceful JVM/container behaviour** | Exec-form entry point so the JVM is pid 1 and receives SIGTERM; `STOPSIGNAL SIGTERM` stated explicitly; `server.shutdown: graceful` already set in `application.yml`; `-XX:MaxRAMPercentage=75.0` so the heap follows the container's memory limit rather than the host's |
| **Runs the same JAR Maven produced** | The `COPY` names `target/online-assignment-portal-0.0.1-SNAPSHOT.jar` exactly — the artefact `./mvnw package` writes |
| **Identifies the release it is** | `ARG APP_VERSION=unknown` / `ENV APP_VERSION=${APP_VERSION}`, so the version is baked into the artefact at build time and published at `/actuator/info`. The release pipeline passes the same value as the image tag; `unknown` is the honest answer for a build that was given none. See [docker-cd.md](docker-cd.md) §6 |

One package is added beyond the base image: **`curl`**, for the `HEALTHCHECK`. The Temurin images
ship no HTTP client, so a probe written against one would not run, and a container with no
`HEALTHCHECK` reports `none` to `docker ps` — the ordering guarantee `compose.yaml` depends on
would be gone. `--no-install-recommends` and the removal of `/var/lib/apt/lists` keep the addition
to the binary and its own dependencies. It is an operational probe, not a development tool: the
image still has no compiler and no build tool.

Where a base image genuinely cannot carry a probe — a distroless runtime, for instance — the honest
outcome is a container with no `HEALTHCHECK`, and this is the trade that was made instead.

### 2.3 Why there is no Maven build in the Dockerfile

The obvious shape for a "multi-stage build" is a Maven stage that runs `mvn package` and a runtime
stage that copies the result. This repository does not do that, and the reason is the whole point of
the exercise:

> The artefact the test suites validated and the artefact that runs in the container must be the
> same bytes.

A Maven build inside the image would be **a second build process**. It would resolve dependencies
without the wrapper's pinned Maven 3.9.16, at a different time, possibly against different upstream
metadata — and then the thing deployed would be a JAR that no test ever ran
([CLAUDE.md §1](../../CLAUDE.md), §10).

So the stages are divided by a different line. What is genuinely worth doing twice is the part that
is *packaging*, not *building*:

```
stage: unpack            FROM eclipse-temurin:21-jre
                         COPY  target/online-assignment-portal-0.0.1-SNAPSHOT.jar application.jar
                         RUN   java -Djarmode=tools -jar application.jar extract --layers …

stage: runtime           FROM eclipse-temurin:21-jre
                         COPY  --from=unpack  dependencies/           ./
                         COPY  --from=unpack  spring-boot-loader/     ./
                         COPY  --from=unpack  snapshot-dependencies/  ./
                         COPY  --from=unpack  application/            ./
                         USER  portal
```

Spring Boot's repackager already groups the archive's contents into layers ordered by how often
they change — third-party dependencies, the loader, snapshot dependencies, and this project's own
classes. Unpacking them in a throwaway stage lets the final stage write them as **four separate
Docker layers**, so a rebuild after a change to one Java class rewrites only `application/` and
reuses the rest from cache, instead of re-writing a ~60 MB archive as a single layer on every
commit. The runtime image is unchanged in behaviour; it is only cheaper to rebuild.

The stage boundaries fall where they do because Docker's build cache is keyed on layer content. Move
the `COPY` of the JAR, and everything below it rebuilds.

## 3. Building the image

The image packages an artefact that must already exist. Building it without building the JAR first
fails at the `COPY`, naming the missing file — which is the intended failure, because the
alternative is an image built from whatever stale JAR happened to be in `target/`.

```bash
# 1. Produce the artefact. This is the only build process in the repository.
./mvnw clean package -DskipTests

# 2. Package it. No Maven runs inside the image.
docker build -t online-assignment-portal:local .
```

On Windows PowerShell or `cmd.exe`, `./mvnw` is `.\mvnw.cmd`.

To confirm the context really is just the one file — the claim in §2.2 is worth checking rather
than believing:

```bash
docker run --rm --entrypoint sh online-assignment-portal:local -c 'ls -la /app'
```

`/app` holds the four layer directories and nothing else. No `src`, no `pom.xml`, no test.

## 4. The Compose stack

`compose.yaml` at the repository root declares the whole system: `app` and `db`, a private network,
a named volume each, health checks on both, `restart: unless-stopped` on both, and explicit port
publishing. It is the single Compose file in the repository — the database definition used to live
alone at `deploy/docker-compose.yml`, and moved here when the image gave an `app` service something
to reference.

```bash
# First time, and after any change to application code. `--build` makes Compose run
# step 2 of §3; it cannot run step 1, so a change to a Java class is invisible to the
# image until `./mvnw clean package` has produced a new JAR:
./mvnw clean package -DskipTests
docker compose up -d --build

# Just the database — the other way to run the application is on the host,
# `./mvnw spring-boot:run`, which connects to it through the published port:
docker compose up -d db
```

| Published on the host | Service | Why |
| --- | --- | --- |
| `127.0.0.1:8081` → `app:8080` | `app` | 8081, not 8080: Jenkins owns 8080 on the machine that runs this pipeline (`scripts/ci/env.cmd`). Loopback only — a stack started from a checkout is nobody else's business |
| `127.0.0.1:3306` → `db:3306` | `db` | A host-run application connects from outside the Compose network. Loopback only |

The two services reach each other by **service name** on the Compose network; the host ports above
are for things running on the host, not for the containers.

> **`.` and `.env`.** Because this file is at the repository root, Compose's project directory is
> the repository root, and `.env` is loaded from there. That is the file `.env.example` has always
> described.

## 5. The container lifecycle demonstration

The nine operations, run against the image directly rather than through Compose, so that each one is
observable in isolation. Start from the repository root with the image already built (§3).

The demonstration runs the application container by hand, joined to the Compose stack's network, so
it shares the database the composition owns while leaving lifecycle control to `docker` itself.
Compose names that network `<project>_<network>`, and the project is pinned by `name:` at the top of
`compose.yaml`.

```bash
# The database has to exist first — the application fails fast without it.
export DB_PASSWORD='a-local-password'
export DB_ROOT_PASSWORD='a-local-root-password'
docker compose up -d --wait db
```

### 1. `build` — turn the JAR into an image

```bash
docker build -t online-assignment-portal:local .
docker images online-assignment-portal
```

### 2. `tag` — give it the name a given revision is known by

A tag is a label on an image id, not a copy: the two names below refer to the same layers. The
pipeline tags with `IMAGE_TAG` (`Jenkinsfile`, `Docker Build` stage) — the release version on a
tagged revision, the abbreviated commit on anything else — so an image found on a machine names
both the revision that produced it and, for a release, the version `APP_VERSION` was baked from.

```bash
docker tag online-assignment-portal:local online-assignment-portal:0.0.1-SNAPSHOT
docker images online-assignment-portal
```

### 3. `run` — start a container

```bash
docker run -d \
  --name portal-demo \
  --network online-assignment-portal_portal \
  -p 127.0.0.1:8090:8080 \
  -e DB_URL='jdbc:mysql://db:3306/assignment_portal' \
  -e DB_USERNAME='assignment_portal' \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e UPLOAD_STORAGE_PATH='/var/lib/portal/uploads' \
  -v assignment-portal-uploads:/var/lib/portal/uploads \
  online-assignment-portal:local
```

| Flag | Why it is there |
| --- | --- |
| `--network` | The database is a container on the Compose network, reachable only by name from inside it |
| `-p 127.0.0.1:8090:8080` | 8090, so this container does not collide with the Compose stack's 8081 — the two are meant to be observable side by side |
| `-e DB_URL=…//db:3306/…` | `db`, not `localhost`: inside the container, `localhost` is the container |
| `-v assignment-portal-uploads:…` | **The named volume** — the point of §6. Without it, this container's uploads die with the container |

The application is up when the health check says so, not when `docker run` returns:

```bash
until [ "$(docker inspect -f '{{.State.Health.Status}}' portal-demo)" = healthy ]; do sleep 1; done
docker inspect -f '{{.State.Health.Status}}' portal-demo
```

`http://localhost:8090/` now serves the portal. Sign-in needs an account: the stack starts with
seeding off (§7), so either enable it or insert a user through the application's own service.

### 4. `ps` — what is running

```bash
docker ps                      # running containers
docker ps -a                   # including stopped ones
docker compose ps              # the whole stack, with its health
```

The `STATUS` column reads `Up 30 seconds (healthy)`. A container that has started but is failing
its probe reads `(unhealthy)` and one with no probe reads `(none)` — worth knowing, because
"Up" alone says nothing about whether the application is answering.

### 5. `logs` — what it is saying

```bash
docker logs portal-demo            # everything so far
docker logs -f portal-demo         # follow
docker logs --tail 50 portal-demo  # the last 50 lines
docker compose logs -f app
```

Every line starts with `Picked up JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75.0` on **stderr**. That
is the mechanism of the Dockerfile's memory setting working, not a warning.

### 6. `stop` / 7. `start` — pause and resume without replacing

```bash
docker stop portal-demo
docker ps -a                       # STATUS: Exited (143) — 143 is SIGTERM, the graceful path
docker start portal-demo
```

`Exited (143)` is the good outcome. It means the JVM received `SIGTERM`, Tomcat stopped accepting
and drained, and the process chose its own exit — which is the pid-1 arrangement in §2.2 working.
`Exited (137)` would mean `SIGKILL`: the grace period expired and requests were cut off. A container
that takes the full ten seconds and then reports 137 is one whose ~10 s shutdown is too short for
what the application is doing.

`stop` and `start` keep the container, and therefore everything about it apart from the process.

### 8. `restart` — stop, then start, atomically

```bash
docker restart portal-demo
```

`restart` is `stop` followed by `start` against the same container. It is worth being precise about
what it is *not*: it does not rebuild anything, does not re-read the image, and does not pick up a
newer `:local`. Only `build` (step 1) followed by removing and re-running the container does that.

### 9. `remove` — delete the container, keep the volumes

```bash
docker rm -f portal-demo
docker ps -a                   # gone
docker volume ls               # assignment-portal-uploads and …-mysql-data: still there
```

This is the operation §6 is about. The container is gone; **the data is not**, because it was never
in the container.

Then prove it, by starting a replacement from the same image with the same volume:

```bash
docker run -d --name portal-demo-2 --network online-assignment-portal_portal \
  -p 127.0.0.1:8090:8080 \
  -e DB_URL='jdbc:mysql://db:3306/assignment_portal' \
  -e DB_USERNAME='assignment_portal' \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e UPLOAD_STORAGE_PATH='/var/lib/portal/uploads' \
  -v assignment-portal-uploads:/var/lib/portal/uploads \
  online-assignment-portal:local
```

Every submission listed before the replacement is listed after it, and every file downloads. That is
the demonstration the persistence requirement asks for.

### Tearing the stack down

```bash
docker compose down          # stop and remove containers and the network. Volumes survive
docker compose down -v       # the same, and DESTROYS both volumes
```

`down -v` is what `scripts/ci/stop-environment.cmd` runs, and correctly so — a CI database that
survived the run would carry one build's submissions into the next. Do not reach for it to tidy up
after a stack you wanted to keep.

## 6. Persistence: what survives what

Two volumes, and the rule they implement is [ADR-004](../adr/ADR-004-filesystem-volume-and-db-metadata.md):
**metadata in MySQL, bytes on a volume, and the container filesystem is never the only copy of
anything** ([CLAUDE.md §7](../../CLAUDE.md) rule 7).

| Volume | Mounted at | Holds |
| --- | --- | --- |
| `assignment-portal-mysql-data` | `db:/var/lib/mysql` | Every row: users, submissions, status history |
| `assignment-portal-uploads` | `app:/var/lib/portal/uploads` | Every submitted PDF |

| Operation | Database rows | Uploaded files | Container |
| --- | --- | --- | --- |
| `docker restart portal-demo` | kept | kept | same container |
| `docker compose up -d --force-recreate app` | kept | kept | **replaced** |
| `docker rm -f` + a new `docker run` | kept | kept | **replaced** |
| Building and deploying a new image | kept | kept | **replaced** |
| `docker compose down` | kept | kept | removed |
| `docker compose down -v` | **destroyed** | **destroyed** | removed |

The two rows that matter are the third and fourth: replacing the application container — the
operation a deployment performs on every release — takes neither the database nor the uploaded
files with it. The application container is disposable; the state is not in it.

This is only true because `UPLOAD_STORAGE_PATH` is set to the **volume's mount point** and not to
`./uploads`. The two are easy to confuse, and the difference is silent: with the default the
application works perfectly and stores every submission in the container's writable layer, where the
next replacement destroys all of them. `compose.yaml` sets the value explicitly for exactly this
reason, and deliberately does **not** read `UPLOAD_STORAGE_PATH` from `.env`, where it is set to
`./uploads` for host runs.

To see it directly:

```bash
docker compose exec app ls -la /var/lib/portal/uploads
docker volume inspect assignment-portal-uploads
```

## 7. Configuration and secrets

The image carries no configuration and no credential. Every value is an environment variable, which
is what lets one artefact be promoted from environment to environment without being rebuilt
([CLAUDE.md §8](../../CLAUDE.md)).

Compose reads `.env` from the repository root. Copy `.env.example` to `.env`, which is git-ignored,
and fill it in. `DB_PASSWORD` and `DB_ROOT_PASSWORD` have **no default** anywhere — neither in
`application.yml` nor in `compose.yaml`, where `${DB_PASSWORD:?…}` makes Compose refuse to start
rather than start a database with an empty password.

Two variables are Compose-only and are documented in `.env.example`:

| Variable | Default | Meaning |
| --- | --- | --- |
| `APP_HOST_PORT` | `8081` | The host port the application is published on. 8081, not 8080, because Jenkins owns 8080 on this pipeline's host |
| `APP_IMAGE_TAG` | `local` | The image tag the `app` service runs. A deployment sets this to the commit the pipeline built |

**Seeding is off.** The stack starts with an empty `users` table, and that is the correct default
for a file a deployment also uses. `DevelopmentUserSeeder` needs three things at once — the `dev`
profile, `APP_SEED_ENABLED=true`, and an email *and* password for the account — and the passwords
have no default in the code. A developer who wants the demo accounts sets all of it in `.env`, which
no shared environment has. `compose.yaml` passes the variables through empty, which is safe rather
than merely tidy: the seeder tests each value with `StringUtils.hasText`, false for an empty string
exactly as for an absent one, so an empty password skips the account instead of creating one with a
blank credential.

## 8. Verification status

**Nothing in this document has been executed.** No `docker build`, no `docker compose up`, no
lifecycle step. The sandbox this was written in had no route to a registry and no working Docker
CLI, so the artefacts are **written, not proven** — and, per
[the evidence rules](../evidence/README.md), nothing here is presented as observed.

| Claim | Status |
| --- | --- |
| The Dockerfile is syntactically valid and the build succeeds | **Not run** |
| The image starts and `/actuator/health` reports `UP` | **Not run** |
| `org.springframework.boot.loader.launch.JarLauncher` is the correct launcher class in Boot 4.1.1 | **Not run** — the class name is Boot 3.2+ naming and is expected to be unchanged in 4.x, but it is a reading, not a result. A wrong name fails loudly at container start |
| `java -Djarmode=tools … extract --layers` is the correct jarmode in Boot 4.1.1 | **Not run** — `tools` replaced `layertools` in Boot 3.3; the older name is expected to be gone in Boot 4 |
| `snapshot-dependencies/` exists after extraction | **Guarded, not observed** — this project has no `-SNAPSHOT` dependencies, so whether the extractor writes an empty directory is an implementation detail. The Dockerfile runs `mkdir -p` on it rather than assuming |
| The container runs as uid 1001 and not root | **Not run** — `docker inspect -f '{{.Config.User}}'` and `id` inside the container would settle it |
| Uploaded files survive container replacement | **Not run** — §5 step 9 and the table in §6 are the procedure |
| The base image digest | **Not resolved** — §2.1. The tag is pinned; the digest is not |
| The Compose stack starts, and `app` waits for `db` to be healthy | **Not run** |
| `SPRING_PROFILES_ACTIVE: ${SPRING_PROFILES_ACTIVE:-}` — an *empty* value — is accepted as "no active profiles" | **Not run** — this is the documented reading and the reason the pass-through in §7 is safe, but it was reasoned about rather than observed. If it is wrong the container fails at startup with a profile-bind error, loudly, on the first `docker compose up` |
| The application applies its Flyway migrations and starts against the Compose database | **Not run** |

The commands to close each row are the ones in this document. Run them from the repository root and
record the output under [`docs/evidence/`](../evidence/) as
[the evidence rules](../evidence/README.md) §2 require.

## 9. Known gaps

| Gap | What would close it |
| --- | --- |
| **Nothing here has run.** §8. | The commands in §3, §4 and §5, once. |
| **The base image is pinned by tag, not digest.** A patch release can change under a rebuild. | Resolve the digest (§2.1) and record it. |
| **No registry for the local stack.** This document builds and tags an image locally; publishing it is the release pipeline's job and is not part of the local lifecycle. | Closed for releases: the `Publish Image` stage pushes `IMAGE_REPOSITORY:<version>` to Docker Hub — [docker-cd.md](docker-cd.md) §3. The local path here remains deliberately unpushed. |
| **`app` is rebuilt, not pulled, in `compose.yaml`.** That is correct for the local case — it is how a working-tree change reaches a container without a release. | Closed for deployments: `deploy/compose.prod.yaml` declares `image:` only and no `build:`, and `deploy-on-host.sh` runs `up -d --no-build`, so a deployment uses the pushed image or fails — [docker-cd.md](docker-cd.md) §3 and §5. |
| **No backup or restore procedure for either volume.** A volume that has never been restored is an untested assumption, and both of this system's copies of a submission live on one. | [ADR-006](../adr/ADR-006-docker-compose-composition.md) already lists this as a follow-up. |
| **`ADMIN` has no admin UI**, and the stack seeds no accounts, so a fresh local stack has no way in until seeding is enabled or a user is created. | Deliberate; see §7. |
