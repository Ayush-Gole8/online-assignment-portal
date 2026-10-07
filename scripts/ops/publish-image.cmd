@echo off
setlocal

rem ===========================================================================
rem Tag and publish the release image. Step 3 and 4 of the release process.
rem
rem What this does
rem   Takes the image the Docker Build stage produced locally and re-tags it into
rem   the registry path, under the release version and nothing else, then pushes
rem   it. Tag and push are separate operations and separate steps in the console,
rem   because a tag that exists locally and an image that exists in the registry
rem   are different claims, and the second is the one a deployment depends on.
rem
rem Why this does not build
rem   The build happened once, in the Docker Build stage, and the artefact it
rem   packaged is the JAR `./mvnw package` produced after the unit, integration and
rem   browser suites had passed. Building again here would resolve dependencies a
rem   second time, at a second moment, and push the result under a version that was
rem   only tested for the first build (CLAUDE.md §1, §10).
rem
rem Why the tag is a version and never `latest`
rem   ADR-006: images are referenced by immutable tag. `latest` is not a version —
rem   it is a pointer that moves, which makes a deployment irreproducible, a rollback
rem   a guess, and an incident investigation unable to say what was running. The
rem   script refuses the word outright rather than trusting the caller.
rem
rem   What this costs: there is no friendly name for "the current release", so a
rem   deployment names the version it wants. That is the intended trade. Rollback
rem   is redeploying an earlier version, which works precisely because every
rem   release kept its own name.
rem
rem Environment
rem   RELEASE_VERSION   REQUIRED. The version from release-version.cmd — the git
rem                     tag without its leading `v`.
rem   IMAGE_REPOSITORY  REQUIRED. `<account>/<name>` on Docker Hub.
rem   LOCAL_IMAGE       defaulted in env.cmd
rem ===========================================================================

call "%~dp0env.cmd"

if not defined RELEASE_VERSION (
    echo [publish-image] FAILED: RELEASE_VERSION is not set. Run release-version.cmd first.
    exit /b 1
)

if not defined IMAGE_REPOSITORY (
    echo [publish-image] FAILED: IMAGE_REPOSITORY is not set.
    echo [publish-image] Expected the Docker Hub repository, as ^<account^>/^<name^>, from the Jenkins IMAGE_REPOSITORY parameter.
    exit /b 1
)

rem Both values become an image reference, and both were produced somewhere other
rem than this script. They are checked here rather than trusted, because the
rem failure a bad value produces is not a failed push — it is a successful push to
rem somewhere nobody intended, which nobody notices.
set "VERSION_OK="
for /f "delims=" %%v in ('powershell -NoProfile -Command "if ('%RELEASE_VERSION%' -match '^[0-9]+\.[0-9]+\.[0-9]+$') { 'ok' }"') do set "VERSION_OK=%%v"

if not defined VERSION_OK (
    echo [publish-image] FAILED: RELEASE_VERSION=%RELEASE_VERSION% is not a version of the form X.Y.Z.
    exit /b 1
)

rem Docker Hub requires lowercase repository names, and a name without a `/` has
rem no account, which would publish under whatever the agent happens to be logged
rem in as. Both are refused here.
set "REPO_OK="
for /f "delims=" %%v in ('powershell -NoProfile -Command "if ('%IMAGE_REPOSITORY%' -match '^[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*){1,2}$') { 'ok' }"') do set "REPO_OK=%%v"

if not defined REPO_OK (
    echo [publish-image] FAILED: IMAGE_REPOSITORY=%IMAGE_REPOSITORY% is not a valid repository name.
    echo [publish-image] Expected ^<account^>/^<name^> in lowercase, for example ^<account^>/assignment-portal.
    exit /b 1
)

set "SOURCE_IMAGE=%LOCAL_IMAGE%:%RELEASE_VERSION%"
set "TARGET_IMAGE=%IMAGE_REPOSITORY%:%RELEASE_VERSION%"

echo [publish-image] Tagging %SOURCE_IMAGE% as %TARGET_IMAGE%...

rem Fails when the local image does not exist, which is the correct outcome: the
rem revision under build did not reach a Docker Build, and publishing a version
rem has nothing to publish.
docker tag "%SOURCE_IMAGE%" "%TARGET_IMAGE%"

if errorlevel 1 (
    echo [publish-image] FAILED: %SOURCE_IMAGE% does not exist, so there is nothing to publish.
    echo [publish-image] The Docker Build stage produces it. Check whether that stage ran and whether the build was green.
    exit /b 1
)

echo [publish-image] Pushing %TARGET_IMAGE%...

rem docker prints the manifest digest on success. It is left in the console
rem deliberately: it is the one identifier that is the same for everyone who pulls
rem this release, and it is what an incident report should quote.
docker push "%TARGET_IMAGE%"

if errorlevel 1 (
    echo [publish-image] FAILED: the push was refused.
    echo [publish-image] Check that the access token has write access to %IMAGE_REPOSITORY%.
    exit /b 1
)

echo [publish-image] Published %TARGET_IMAGE%.
endlocal
exit /b 0
