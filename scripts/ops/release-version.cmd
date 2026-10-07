@echo off
setlocal enabledelayedexpansion

rem ===========================================================================
rem Identify the release. Step 1 of the release process.
rem
rem What this does
rem   Answers one question — is the revision under build a release, and if so
rem   which one — and writes the answer where the rest of the pipeline can read
rem   it. It answers from the GIT TAG and from nothing else.
rem
rem Why the tag and not a build parameter
rem   The tag is the thing a human created deliberately, in a reviewable place,
rem   on a commit that has already passed CI. A version typed into a build form
rem   is a version with no such record, and a pipeline that accepts one can
rem   publish a "release" that no tag ever named. RELEASE_VERSION exists here
rem   only as an assertion — if it is set it must agree with the tag, and the
rem   build fails when it does not. It can never introduce a version.
rem
rem Why the answer is a FILE and not an environment variable
rem   Jenkins runs every `bat` step in its own cmd.exe, so a variable set here
rem   does not survive into the next stage. The stage reads the file and assigns
rem   the value to env.RELEASE_VERSION itself — which is also why the file is
rem   written under target\ and not to the workspace root.
rem
rem Outputs
rem   target\release-version.txt   the version, such as 1.0.1. ABSENT when the
rem                                revision is not a release — absence is the
rem                                negative answer, and a caller that finds no
rem                                file must treat the build as unreleased.
rem   Console                      a line ending in IS_RELEASE=true|false, so a
rem                                console reader sees the decision and its reason.
rem
rem Environment
rem   TAG_NAME          the tag, when Jenkins built a tag ref (multibranch with
rem                     tag discovery). Preferred over asking git, because it is
rem                     what the job was actually asked to build.
rem   RELEASE_VERSION   optional. When set it must equal the tag's version,
rem                     otherwise the script fails — see above.
rem   RELEASE_VERSION_FILE  default target\release-version.txt
rem ===========================================================================

if not defined RELEASE_VERSION_FILE set "RELEASE_VERSION_FILE=target\release-version.txt"

if not exist "target" mkdir "target"
if exist "%RELEASE_VERSION_FILE%" del /q "%RELEASE_VERSION_FILE%"

rem ---------------------------------------------------------------------------
rem 1. Find the tag on this exact commit.
rem ---------------------------------------------------------------------------
set "TAG="

if defined TAG_NAME set "TAG=%TAG_NAME%"

rem --exact-match asks git "is THIS commit tagged", not "what is the nearest
rem tag". The distinction is the whole question: without it, every commit after
rem v1.0.0 would report v1.0.0 and the pipeline would republish a release from
rem ordinary work on develop.
if not defined TAG (
    for /f "delims=" %%t in ('git describe --tags --exact-match HEAD 2^>nul') do set "TAG=%%t"
)

if not defined TAG (
    if defined RELEASE_VERSION (
        echo [release-version] FAILED: RELEASE_VERSION=%RELEASE_VERSION% was requested, but this commit carries no tag.
        echo [release-version] A release is identified by its git tag. Tag the commit and build the tag, or leave RELEASE_VERSION unset for a non-release build.
        exit /b 1
    )
    echo [release-version] This revision carries no tag, so it is not a release.
    echo [release-version] IS_RELEASE=false
    exit /b 0
)

echo [release-version] Tag on this revision: %TAG%

rem ---------------------------------------------------------------------------
rem 2. Turn the tag into a version: drop the leading `v`.
rem ---------------------------------------------------------------------------
set "VERSION=%TAG%"
if /i "%VERSION:~0,1%"=="v" set "VERSION=%VERSION:~1%"

rem The shape is validated with PowerShell rather than findstr, because the
rem version is about to become an image tag and a path on a deployment host and a
rem near-miss is not worth the regex dialect. `1.0` and `1.0.0-beta` are refused:
rem the release rule is vX.Y.Z, and a tag that nearly matches is a tag nobody
rem agreed on.
set "VERSION_OK="
for /f "delims=" %%v in ('powershell -NoProfile -Command "if ('%VERSION%' -match '^[0-9]+\.[0-9]+\.[0-9]+$') { 'ok' }"') do set "VERSION_OK=%%v"

if not defined VERSION_OK (
    if defined RELEASE_VERSION (
        echo [release-version] FAILED: tag %TAG% is not a release tag. Expected v followed by X.Y.Z, such as v1.0.1.
        exit /b 1
    )
    echo [release-version] Tag %TAG% is not a release tag (expected vX.Y.Z), so this is not a release.
    echo [release-version] IS_RELEASE=false
    exit /b 0
)

rem ---------------------------------------------------------------------------
rem 3. If a version was requested as well, it must be the same one.
rem ---------------------------------------------------------------------------
if defined RELEASE_VERSION (
    if /i not "%RELEASE_VERSION%"=="%VERSION%" (
        echo [release-version] FAILED: RELEASE_VERSION=%RELEASE_VERSION% but the tag on this commit is %TAG%, which is version %VERSION%.
        echo [release-version] The git tag is authoritative. Remove the parameter, or tag this commit with the version you intend to release.
        exit /b 1
    )
)

rem Redirected BEFORE the text, and the version unquoted, so that the file holds
rem `1.0.1` and nothing else. `echo %VERSION%>"file"` would work for this value
rem and is still the wrong thing to write: cmd parses a redirect operator
rem immediately after a digit as a stream handle, and `echo 1.0.1>file` is one
rem character away from `echo 2>file` — a rule whose failure mode is an empty
rem version file and a deployment tagged with nothing.
>"%RELEASE_VERSION_FILE%" echo %VERSION%

echo [release-version] Release version %VERSION% ^(from tag %TAG%^), written to %RELEASE_VERSION_FILE%.
echo [release-version] IS_RELEASE=true

endlocal
exit /b 0
