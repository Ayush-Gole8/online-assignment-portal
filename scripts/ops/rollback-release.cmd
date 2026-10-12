@echo off
setlocal

rem ===========================================================================
rem Restore a deployment to a version known to have worked.
rem
rem This is the OPERATIONAL rollback command — the one a person runs when a
rem release has gone wrong — and it is also what deploy-release.cmd calls on its
rem failure path, so the automatic rollback and the manual one are one code path.
rem That is the point: the rollback that runs at 3am under load is the rollback
rem that has been read, reviewed, and run by hand.
rem
rem What it does — the seven steps the requirement names, in order
rem   1. identify the last known stable version   (the host's release ledger, or
rem                                                ROLLBACK_VERSION)
rem   2. report what it is about to do            (it prints the target and why)
rem   3. restore that exact container image       (rollback-on-host.sh, on the host)
rem   4. restart the application                  (the container is recreated on it)
rem   5. wait for health                          (scripts\ci\wait-for-health.cmd,
rem                                                /actuator/health from the AGENT
rem                                                over the network)
rem   6. run smoke verification                    (scripts\ops\smoke-test.cmd —
rem                                                including a business-route check)
rem   7. report success or failure                 (target\rollback-report.txt, and
rem                                                a `success` row in the ledger)
rem
rem Steps 3 and 4 happen ON the host; steps 5 and 6 happen HERE, from the agent,
rem because "the container can reach itself" is not the claim a rollback has to
rem establish. A rollback that only proved the container started would report
rem success for a version that is up and not serving.
rem
rem What it deliberately does NOT do
rem   It does not reverse a database migration. Flyway is forward-only (ADR-003),
rem   so this restores the previous APPLICATION against the schema the failed
rem   release left behind. That is safe only when the release's schema change was
rem   backward-compatible — see docs/operations/rollback.md §2, which states the
rem   rule this script cannot enforce.
rem
rem   It carries no credential. The SSH key is bound by `sshagent` in the calling
rem   stage. The database password and the registry credential for pulling live in
rem   the host's own `.env`, which this process never reads.
rem
rem Environment
rem   DEPLOY_HOST        REQUIRED. user@host.
rem   DEPLOY_ENV         REQUIRED. staging or production.
rem   ROLLBACK_VERSION   optional. The exact version to restore. When unset, the
rem                      HOST decides from its ledger and says why.
rem   ROLLBACK_PLAN      optional. When defined, only IDENTIFY the target and exit
rem                      without changing anything — the standalone form of step 1.
rem   DEPLOY_PATH, APP_HOST_PORT, DEPLOY_BASE_URL, *_TIMEOUT_SECONDS — env.cmd
rem
rem Exit codes
rem   0  the target version is running and passed health and smoke verification
rem   1  the rollback failed, or the restored version did not verify
rem ===========================================================================

call "%~dp0env.cmd"

if not defined DEPLOY_HOST (
    echo [rollback-release] FAILED: DEPLOY_HOST is not set. Expected user@host.
    echo [rollback-release] It has no default on purpose: a rollback aimed at the wrong host by an unset variable is worse than no rollback.
    exit /b 1
)

if not defined DEPLOY_ENV (
    echo [rollback-release] FAILED: DEPLOY_ENV is not set. Expected staging or production.
    exit /b 1
)

if /i not "%DEPLOY_ENV%"=="staging" if /i not "%DEPLOY_ENV%"=="production" (
    echo [rollback-release] FAILED: DEPLOY_ENV='%DEPLOY_ENV%' is not a deployable environment. Expected 'staging' or 'production'.
    exit /b 1
)

if not exist "target" mkdir "target"
set "ROLLBACK_LOG=target\ci-rollback.log"
set "ROLLBACK_REPORT=target\rollback-report.txt"
if exist "%ROLLBACK_LOG%" del /q "%ROLLBACK_LOG%"
if exist "%ROLLBACK_REPORT%" del /q "%ROLLBACK_REPORT%"

rem BatchMode makes ssh fail rather than prompt; a prompt in a Jenkins `bat` step
rem has nobody to answer it and turns a missing key into a stage timeout.
set "SSH_OPTS=-o BatchMode=yes -o ConnectTimeout=15"

rem ---------------------------------------------------------------------------
rem Plan mode: identify the target and stop. Nothing on the host is changed.
rem ---------------------------------------------------------------------------
if defined ROLLBACK_PLAN (
    echo [rollback-release] Identifying the version to restore. Nothing will be changed.
    ssh %SSH_OPTS% %DEPLOY_HOST% "sh %DEPLOY_PATH%/rollback-on-host.sh plan %ROLLBACK_VERSION% %DEPLOY_ENV%"
    exit /b %ERRORLEVEL%
)

rem ---------------------------------------------------------------------------
rem 1. Make sure the host holds the tooling this rollback runs on. A host reached
rem    mid-incident may be holding a set of scripts from an older release, so the
rem    same files a deployment would place are placed before the restore.
rem ---------------------------------------------------------------------------
call "%~dp0copy-host-scripts.cmd"
if errorlevel 1 goto :transport_failed

rem ---------------------------------------------------------------------------
rem 2 to 4. Restore the exact image version and restart the application on it.
rem    The host chooses the target when ROLLBACK_VERSION is unset, and prints both
rem    the target it chose and the reason, so a rollback is explainable from its
rem    own output rather than from whoever ran it.
rem ---------------------------------------------------------------------------
set "REMOTE_COMMAND=sh %DEPLOY_PATH%/rollback-on-host.sh rollback"
if defined ROLLBACK_VERSION set "REMOTE_COMMAND=%REMOTE_COMMAND% %ROLLBACK_VERSION%"
set "REMOTE_COMMAND=%REMOTE_COMMAND% %DEPLOY_ENV%"

echo [rollback-release] Rolling back %DEPLOY_ENV% on %DEPLOY_HOST%...

ssh %SSH_OPTS% %DEPLOY_HOST% "%REMOTE_COMMAND%" >"%ROLLBACK_LOG%" 2>&1
set "ROLLBACK_STATUS=%ERRORLEVEL%"

type "%ROLLBACK_LOG%"

set "TARGET_VERSION="
for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"rolled-back-to=" "%ROLLBACK_LOG%"') do set "TARGET_VERSION=%%b"

if not "%ROLLBACK_STATUS%"=="0" goto :rollback_failed
if not defined TARGET_VERSION goto :rollback_failed

echo [rollback-release] The host is now running %TARGET_VERSION% (was %ROLLBACK_VERSION%).

rem ---------------------------------------------------------------------------
rem 5 and 6. Verify it, from HERE — health over the network, then the smoke test.
rem    The expected version is the version that was restored, so the smoke test's
rem    step 2 proves the restored image is the one answering, not merely that
rem    something healthy is.
rem ---------------------------------------------------------------------------
set "RELEASE_VERSION=%TARGET_VERSION%"

if not defined DEPLOY_BASE_URL (
    for /f "delims=" %%u in ('powershell -NoProfile -Command "$h = '%DEPLOY_HOST%'; if ($h.Contains('@')) { $h = $h.Split('@')[-1] }; 'http://' + $h + ':' + '%APP_HOST_PORT%'"') do set "DEPLOY_BASE_URL=%%u"
)

set "HEALTH_URL=%DEPLOY_BASE_URL%/actuator/health"
set "HEALTH_TIMEOUT_SECONDS=%DEPLOY_HEALTH_TIMEOUT_SECONDS%"
call "%~dp0..\ci\wait-for-health.cmd"
if errorlevel 1 goto :verify_failed

call "%~dp0smoke-test.cmd"
if errorlevel 1 goto :verify_failed

echo [rollback-release] Container state after the rollback:
ssh %SSH_OPTS% %DEPLOY_HOST% "cd %DEPLOY_PATH% && docker compose -f compose.prod.yaml ps"

rem ---------------------------------------------------------------------------
rem 7. Report. The restored version is recorded as STABLE, because it has now
rem    been verified from outside the machine running it — which is the claim
rem    that makes a version a rollback target in the first place.
rem ---------------------------------------------------------------------------
call :record_ledger "success" "verified from the agent after rollback to %TARGET_VERSION%"
call :write_report "SUCCESS"
type "%ROLLBACK_REPORT%"
echo [rollback-release] Rolled back to %TARGET_VERSION% in %DEPLOY_ENV%.
endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem Failure paths
rem ---------------------------------------------------------------------------
:transport_failed
echo [rollback-release] FAILED: the deployment host could not be reached.
echo [rollback-release] Nothing was changed on the host, so the deployment is in whatever state it was already in.
echo [rollback-release] Check that the ssh-agent holds a key the host accepts, and that the host key is in this agent's known_hosts.
call :write_report "FAILED, HOST UNREACHABLE - NOTHING CHANGED"
endlocal
exit /b 1

:rollback_failed
echo [rollback-release] FAILED: the host did not restore a version.
echo [rollback-release] The host-side output above names the step that failed. The usual cause is that the image for the target version cannot be pulled, or that the ledger records no successful release to return to.
call :write_report "FAILED, NOTHING RESTORED"
endlocal
exit /b 1

:verify_failed
echo [rollback-release] FAILED: %TARGET_VERSION% was restored but did not verify.
echo [rollback-release] The host is running a version that answers neither health nor the smoke test. This needs a human: inspect the host's container logs, and check the database and the uploads volume, before deciding whether to go forward to a known-good image or restore from a backup (docs/operations/rollback.md §6).
call :write_report "FAILED, RESTORED %TARGET_VERSION% DID NOT VERIFY"
endlocal
exit /b 1

:record_ledger
rem %~1 is the outcome, %~2 the note, single-quoted for the remote shell.
ssh %SSH_OPTS% %DEPLOY_HOST% "cd %DEPLOY_PATH% && DEPLOY_DIR=%DEPLOY_PATH% sh release-ledger.sh record %TARGET_VERSION% %DEPLOY_ENV% %~1 '%~2'"
if errorlevel 1 (
    echo [rollback-release] WARNING: the host ledger could not be told that %TARGET_VERSION% is '%~1'.
    echo [rollback-release] The rollback itself is unaffected, but the ledger on the host may now under-report which versions are known to work.
)
exit /b 0

:write_report
echo ASSIGNMENT PORTAL ROLLBACK REPORT >"%ROLLBACK_REPORT%"
echo ==================================== >>"%ROLLBACK_REPORT%"
echo target version    : %TARGET_VERSION% >>"%ROLLBACK_REPORT%"
echo requested version : %ROLLBACK_VERSION% >>"%ROLLBACK_REPORT%"
echo environment       : %DEPLOY_ENV% >>"%ROLLBACK_REPORT%"
echo host              : %DEPLOY_HOST% >>"%ROLLBACK_REPORT%"
echo smoke base url    : %DEPLOY_BASE_URL% >>"%ROLLBACK_REPORT%"
echo outcome           : %~1 >>"%ROLLBACK_REPORT%"
exit /b 0
