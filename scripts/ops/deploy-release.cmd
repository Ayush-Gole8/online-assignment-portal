@echo off
setlocal

rem ===========================================================================
rem Deploy a release to an environment. Steps 5 to 11 of the release process.
rem
rem What this does
rem   1. copies the composition and the host-side script to the deployment host
rem   2. runs deploy-on-host.sh there, which records the version, pulls that exact
rem      image, and replaces the application container
rem   3. waits for the deployment's own /actuator/health to answer UP, over the
rem      network, from this agent
rem   4. runs the smoke test
rem   5. rolls back to the version that was running, if 3 or 4 failed
rem   6. writes a report of what happened
rem
rem Why the rollback is here and not in a separate "rollback job"
rem   A rollback is not a separate operation; it is the second half of a failed
rem   deployment. Keeping it here means the version to return to is the one that
rem   was running thirty seconds ago, read from the host rather than remembered,
rem   and it means a deployment that fails leaves the system as it found it
rem   without anyone having to notice and act (CLAUDE.md §9).
rem
rem What it deliberately does not do
rem   It carries no credential. The SSH key is bound by `sshagent` in the Deploy
rem   stage and exists only in an agent this process talks to. The database
rem   password and the registry credential for pulling are in the host's own
rem   `.env`, which this process never reads. The only thing that travels from
rem   Jenkins to the host is the release version and the environment name — both
rem   of which are public, and one of which is a git tag.
rem
rem Host key verification is NOT disabled
rem   There is no `-o StrictHostKeyChecking=no` anywhere in this file and none may
rem   be added. Accepting an unknown host key on every connection is the same as
rem   having no host identity at all: it makes a man-in-the-middle indistinguishable
rem   from the deployment target, on the connection that is about to receive a
rem   release. The agent's known_hosts must be provisioned — docs/operations/docker-cd.md
rem   §4 — and the first run against an unprovisioned agent fails with
rem   "Host key verification failed", which is the correct outcome.
rem
rem Environment
rem   RELEASE_VERSION   REQUIRED. From release-version.cmd, via the Release Version stage.
rem   DEPLOY_ENV        REQUIRED. staging or production.
rem   DEPLOY_HOST       REQUIRED. user@host.
rem   DEPLOY_PATH       defaulted in env.cmd (/opt/assignment-portal)
rem   DEPLOY_BASE_URL   optional; derived from DEPLOY_HOST and APP_HOST_PORT when unset
rem ===========================================================================

call "%~dp0env.cmd"

if not defined RELEASE_VERSION (
    echo [deploy-release] FAILED: RELEASE_VERSION is not set. This script deploys a release; it does not decide which one.
    exit /b 1
)

if not defined DEPLOY_HOST (
    echo [deploy-release] FAILED: DEPLOY_HOST is not set. Expected user@host.
    echo [deploy-release] It has no default on purpose: a deployment aimed at the wrong host by an unset variable is the failure the empty default exists to prevent.
    exit /b 1
)

if /i "%DEPLOY_ENV%"=="none" (
    echo [deploy-release] FAILED: DEPLOY_ENV=none means the release was deliberately not deployed. Skipping is the Jenkinsfile's decision; reaching this script with 'none' is a pipeline defect.
    exit /b 1
)

if /i not "%DEPLOY_ENV%"=="staging" if /i not "%DEPLOY_ENV%"=="production" (
    echo [deploy-release] FAILED: DEPLOY_ENV='%DEPLOY_ENV%' is not a deployable environment. Expected 'staging' or 'production'.
    exit /b 1
)

if not exist "target" mkdir "target"
set "DEPLOY_LOG=target\ci-deploy.log"
set "DEPLOY_REPORT=target\deployment-report.txt"
if exist "%DEPLOY_LOG%" del /q "%DEPLOY_LOG%"
if exist "%DEPLOY_REPORT%" del /q "%DEPLOY_REPORT%"

rem Derive the URL the smoke test will use, when the caller did not name one.
rem The host as written may be `user@host`, and this wants the host part only.
if not defined DEPLOY_BASE_URL (
    for /f "delims=" %%u in ('powershell -NoProfile -Command "$h = '%DEPLOY_HOST%'; if ($h.Contains('@')) { $h = $h.Split('@')[-1] }; 'http://' + $h + ':' + '%APP_HOST_PORT%'"') do set "DEPLOY_BASE_URL=%%u"
)

rem BatchMode makes ssh fail rather than prompt. A prompt in a Jenkins `bat`
rem step has no one to answer it: the job would sit until the stage timeout, and
rem report a timeout instead of the missing key or the unverified host key that
rem actually caused it.
set "SSH_OPTS=-o BatchMode=yes -o ConnectTimeout=15"

echo [deploy-release] Deploying %RELEASE_VERSION% to %DEPLOY_ENV% on %DEPLOY_HOST%...
echo [deploy-release] Deployment directory: %DEPLOY_PATH%
echo [deploy-release] Smoke test base URL: %DEPLOY_BASE_URL%

rem ---------------------------------------------------------------------------
rem 1. Place the composition and the host-side script on the target.
rem
rem Both are copied on EVERY release, not only when they have changed. The host
rem then runs the composition and the script that are in the tag being deployed,
rem which is the only version of either that has been reviewed alongside the code
rem it is about to run. A host left holding last month's compose file is a host
rem whose behaviour nobody can predict from the repository.
rem ---------------------------------------------------------------------------
echo [deploy-release] Copying the composition and the deployment script...

ssh %SSH_OPTS% %DEPLOY_HOST% "mkdir -p %DEPLOY_PATH%"
if errorlevel 1 goto :transport_failed

scp %SSH_OPTS% "deploy\compose.prod.yaml" "%DEPLOY_HOST%:%DEPLOY_PATH%/compose.prod.yaml"
if errorlevel 1 goto :transport_failed

scp %SSH_OPTS% "deploy\deploy-on-host.sh" "%DEPLOY_HOST%:%DEPLOY_PATH%/deploy-on-host.sh"
if errorlevel 1 goto :transport_failed

rem ---------------------------------------------------------------------------
rem 2. Deploy. The host records what it was running before it changes anything,
rem    and reports it on the first line of its output, so a rollback needs no
rem    second round trip and no state held here.
rem ---------------------------------------------------------------------------
echo [deploy-release] Running the release on the host...

ssh %SSH_OPTS% %DEPLOY_HOST% "sh %DEPLOY_PATH%/deploy-on-host.sh deploy %RELEASE_VERSION% %DEPLOY_ENV%" >"%DEPLOY_LOG%" 2>&1
set "DEPLOY_STATUS=%ERRORLEVEL%"

type "%DEPLOY_LOG%"

rem The line the host printed before it touched anything. Read from the log
rem rather than remembered, so it is what the host actually had.
set "PREVIOUS_VERSION="
for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"previous=" "%DEPLOY_LOG%"') do set "PREVIOUS_VERSION=%%b"

if not "%DEPLOY_STATUS%"=="0" goto :deploy_failed

rem ---------------------------------------------------------------------------
rem 3. Wait for health, from HERE — over the network, from another machine.
rem
rem   The host already waited for the container's own healthcheck (compose up
rem   --wait). That proves the process started and its probe passed from inside the
rem   container. This proves something else: that the deployment answers requests
rem   arriving from outside it, which is the claim a deployment has to establish.
rem   The two are not the same test and this one is not redundant.
rem
rem   The gate is scripts\ci\wait-for-health.cmd — the same readiness gate the
rem   pipeline uses for its own test environment, including its rule that the body
rem   must be a Spring health document reporting UP. Reusing it here means there is
rem   one definition of "the application is serving" rather than two.
rem ---------------------------------------------------------------------------
set "HEALTH_URL=%DEPLOY_BASE_URL%/actuator/health"
set "HEALTH_TIMEOUT_SECONDS=%DEPLOY_HEALTH_TIMEOUT_SECONDS%"
call "%~dp0..\ci\wait-for-health.cmd"
if errorlevel 1 goto :verify_failed

rem ---------------------------------------------------------------------------
rem 4. Smoke test. Health answers "is it up"; this answers "is the right thing up,
rem    and does it still render", which is what a deployment is actually claiming.
rem ---------------------------------------------------------------------------
call "%~dp0smoke-test.cmd"
if errorlevel 1 goto :verify_failed

echo [deploy-release] Container state after the deployment:
ssh %SSH_OPTS% %DEPLOY_HOST% "cd %DEPLOY_PATH% && docker compose -f compose.prod.yaml ps"

rem ---------------------------------------------------------------------------
rem 5. The report. Written whether the deployment succeeded or failed, because a
rem    deployment record that only exists for the good outcome is a record that
rem    cannot explain the bad one.
rem ---------------------------------------------------------------------------
call :write_report "SUCCESS"

type "%DEPLOY_REPORT%"
echo [deploy-release] Deployed %RELEASE_VERSION% to %DEPLOY_ENV%.
endlocal
exit /b 0

:write_report
echo ASSIGNMENT PORTAL DEPLOYMENT REPORT >"%DEPLOY_REPORT%"
echo ===================================== >>"%DEPLOY_REPORT%"
echo release version : %RELEASE_VERSION% >>"%DEPLOY_REPORT%"
echo environment     : %DEPLOY_ENV% >>"%DEPLOY_REPORT%"
echo host            : %DEPLOY_HOST% >>"%DEPLOY_REPORT%"
echo smoke base url  : %DEPLOY_BASE_URL% >>"%DEPLOY_REPORT%"
echo previous version: %PREVIOUS_VERSION% >>"%DEPLOY_REPORT%"
echo outcome         : %~1 >>"%DEPLOY_REPORT%"
exit /b 0

rem ---------------------------------------------------------------------------
rem Failure paths
rem ---------------------------------------------------------------------------
:transport_failed
echo [deploy-release] FAILED: the deployment host could not be reached.
echo [deploy-release] Nothing was changed on the host, so there is nothing to roll back.
echo [deploy-release] Check that the ssh-agent holds a key the host accepts, and that the host key is in this agent's known_hosts.
call :write_report "FAILED, HOST UNREACHABLE - NOTHING CHANGED"
endlocal
exit /b 1

:deploy_failed
echo [deploy-release] FAILED: the release did not start on the host.
echo [deploy-release] The host-side output above names the step that failed. The pull usually fails because the image for %RELEASE_VERSION% is not in the registry under %DEPLOY_HOST%'s configured repository.
echo [deploy-release] Rolling back to %PREVIOUS_VERSION%...
call :rollback
endlocal
exit /b 1

:verify_failed
echo [deploy-release] FAILED: %RELEASE_VERSION% started but did not verify.
echo [deploy-release] Rolling back to %PREVIOUS_VERSION%...
call :rollback
endlocal
exit /b 1

rem ---------------------------------------------------------------------------
rem Roll back to the version the host was running before this deployment.
rem
rem The rollback is the SAME operation as the deployment, pointed at the previous
rem version — one code path, so the thing exercised on the happy path is the thing
rem that runs when it matters. If there was no previous version (a first
rem deployment), there is nothing to return to and the script says so rather than
rem pretending to have undone anything.
rem ---------------------------------------------------------------------------
:rollback
if not defined PREVIOUS_VERSION (
    echo [deploy-release] No previous version is recorded on the host, so there is nothing to roll back to.
    echo [deploy-release] This was the first deployment to this host. The application is left in whatever state the failed release produced; the host's own container state is printed above.
    call :write_report "FAILED, NOTHING TO ROLL BACK TO"
    exit /b 0
)

if /i "%PREVIOUS_VERSION%"=="%RELEASE_VERSION%" (
    echo [deploy-release] The failed release was redeploying %PREVIOUS_VERSION%, so there is nothing to roll back to.
    call :write_report "FAILED, REDEPLOYMENT - NOTHING TO ROLL BACK TO"
    exit /b 0
)

ssh %SSH_OPTS% %DEPLOY_HOST% "sh %DEPLOY_PATH%/deploy-on-host.sh deploy %PREVIOUS_VERSION% %DEPLOY_ENV%"
if errorlevel 1 (
    echo [deploy-release] FAILED: the rollback to %PREVIOUS_VERSION% also failed. This deployment needs a human.
    call :write_report "FAILED, ROLLBACK TO %PREVIOUS_VERSION% ALSO FAILED"
    exit /b 1
)

echo [deploy-release] Rolled back to %PREVIOUS_VERSION%. The host is serving the version it was serving before this release.
echo [deploy-release] The published image for %RELEASE_VERSION% remains in the registry and is unchanged by this rollback.

call :write_report "FAILED, ROLLED BACK TO %PREVIOUS_VERSION%"
exit /b 0
