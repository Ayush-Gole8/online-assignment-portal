@echo off

rem ===========================================================================
rem Shared defaults for the release and deployment scripts.
rem
rem How to use it
rem   From a script that has already called `setlocal`:
rem
rem       call "%~dp0env.cmd"
rem
rem   Each variable is assigned only when it is not already defined, so Jenkins
rem   — which passes the build parameters in as environment variables — and an
rem   operator running a script by hand can both override any of them. Like
rem   scripts\ci\env.cmd, this file deliberately does NOT call `setlocal` of its
rem   own: the values have to outlive the call.
rem
rem Why one file instead of a default in each script
rem   Four scripts need the same registry, the same deployment path and the same
rem   host port, and Jenkins runs every `bat` step in its own cmd.exe, so nothing
rem   is inherited from one stage to the next. A default copied into four files
rem   is four defaults that will disagree the first time one of them is edited.
rem
rem What is NOT here, and why
rem   No credential VALUE appears in this file, and none may be added to it. The
rem   identifiers below are Jenkins credential IDs — the names of entries in the
rem   credentials store, not secrets themselves — and they are the only way this
rem   file refers to a credential at all. The values are bound into the
rem   environment of a single stage by `withCredentials` (CLAUDE.md §7 rule 1,
rem   §8; SECURITY.md §5).
rem
rem   An identifier is configuration and a value is a secret. The pipeline has to
rem   know which store entry to ask for, which is why the identifiers are here and
rem   reviewable, and it must never be able to read the value, which is why
rem   nothing else is.
rem ===========================================================================

rem ---------------------------------------------------------------------------
rem Registry
rem ---------------------------------------------------------------------------

rem The registry images are published to. Docker Hub, per the release brief.
rem `docker.io` is Docker's default registry; writing it down means a
rem `docker login` and a `docker push` in this pipeline agree about the host even
rem if a future change points one of them somewhere else.
if not defined REGISTRY set "REGISTRY=docker.io"

rem The Jenkins credential holding the Docker Hub username and access token.
rem Bound as DOCKERHUB_USERNAME / DOCKERHUB_TOKEN by `withCredentials` in the
rem Publish Image stage, and consumed only by docker-login.cmd.
if not defined DOCKERHUB_CREDENTIALS_ID set "DOCKERHUB_CREDENTIALS_ID=dockerhub-credentials"

rem The Jenkins credential holding the SSH private key used to reach the
rem deployment host. Bound by `sshagent` in the Deploy stage.
if not defined DEPLOY_SSH_CREDENTIALS_ID set "DEPLOY_SSH_CREDENTIALS_ID=deploy-ssh-key"

rem ---------------------------------------------------------------------------
rem The deployment host
rem ---------------------------------------------------------------------------

rem The directory on the deployment host holding compose.prod.yaml and .env.
rem Both are placed there — the first by every release, the second once by
rem whoever provisions the host (deploy/env.production.example).
if not defined DEPLOY_PATH set "DEPLOY_PATH=/opt/assignment-portal"

rem `user@host`. Deliberately has NO default: a default here would be a hostname
rem committed to the repository, and a deployment aimed at the wrong host by an
rem unset variable is the failure this omission exists to prevent. The Deploy
rem stage refuses to run without it.
rem DEPLOY_HOST is a Jenkins build parameter.

rem Base URL the post-deployment smoke test requests, from the Jenkins agent over
rem the network — not from the host, and not from inside the container, because
rem "the container can reach itself" is not the claim a deployment has to
rem establish. Defaulted from DEPLOY_HOST and APP_HOST_PORT when it is not set
rem explicitly, so the common case needs no extra parameter.
rem DEPLOY_BASE_URL is a Jenkins build parameter.

rem The host port the application is published on, as deploy/compose.prod.yaml
rem reads it. Only used to derive DEPLOY_BASE_URL; it is the host's `.env` that
rem actually decides the binding.
if not defined APP_HOST_PORT set "APP_HOST_PORT=8080"

rem The two directories under DEPLOY_PATH that hold the release record and the
rem backups. Computed on the host from DEPLOY_PATH by deploy/host-common.sh; named
rem here too so the agent-side scripts can refer to a path in a report or a
rem diagnostic line without asking the host for it first.
if not defined DEPLOY_RELEASES_PATH set "DEPLOY_RELEASES_PATH=%DEPLOY_PATH%/releases"
if not defined DEPLOY_BACKUPS_PATH set "DEPLOY_BACKUPS_PATH=%DEPLOY_PATH%/backups"

rem ---------------------------------------------------------------------------
rem Naming
rem ---------------------------------------------------------------------------

rem The local image tag the Docker Build stage produces, before the Publish stage
rem re-tags it into the registry path. The name matches the Maven artifactId, so
rem an image found on a machine names the artefact it came from.
if not defined LOCAL_IMAGE set "LOCAL_IMAGE=online-assignment-portal"

rem The Docker Hub repository, as `<account>/<name>`. Supplied by the Jenkins
rem IMAGE_REPOSITORY parameter and set in the host's `.env`; it has no default
rem here because a guessed account name is a push to the wrong repository, which
rem is both a disclosure and a silent failure.
rem IMAGE_REPOSITORY is a Jenkins build parameter.

rem ---------------------------------------------------------------------------
rem Timeouts
rem ---------------------------------------------------------------------------

rem How long the agent waits for the deployment's /actuator/health to answer UP
rem over the network after `docker compose up --wait` has returned. The container
rem has already reported itself healthy by then, so this is the network round
rem trip and a margin — not a second, longer startup budget.
if not defined DEPLOY_HEALTH_TIMEOUT_SECONDS set "DEPLOY_HEALTH_TIMEOUT_SECONDS=60"

rem How long the smoke test waits for each request.
if not defined SMOKE_TIMEOUT_SECONDS set "SMOKE_TIMEOUT_SECONDS=15"
