@echo off
setlocal

rem ===========================================================================
rem Place the deployment host's files — the composition and the host-side scripts
rem — into DEPLOY_PATH, over scp.
rem
rem Why this is its own script
rem   Two agent-side scripts need the SAME set of files on the host:
rem   deploy-release.cmd before it deploys, and rollback-release.cmd before it
rem   restores. The set is a set: deploy-on-host.sh SOURCES host-common.sh, and
rem   pre-release.sh and rollback-on-host.sh source both. A host holding a new
rem   deploy-on-host.sh over an older host-common.sh is a host that fails at the
rem   next release for a reason nothing on the host explains. Listing the files
rem   twice would be two lists that drift the first time a host script is added —
rem   the same failure scripts/ops/env.cmd exists to prevent for its defaults.
rem
rem The failure that motivates the ordering
rem   Every file is copied on EVERY release and every rollback, not only when it
rem   has changed, so the host runs the versions that are in the tag being
rem   deployed — the only versions reviewed alongside the code they support.
rem
rem Environment
rem   DEPLOY_HOST   REQUIRED. user@host.
rem   DEPLOY_PATH   defaulted in env.cmd (/opt/assignment-portal)
rem   SSH_OPTS      OPTIONAL. BatchMode and timeout flags, set by the caller.
rem
rem Exit codes
rem   0  every file is in place
rem   1  the host could not be reached, or a copy failed. Nothing on the host has
rem      been changed by this run beyond the files already copied.
rem ===========================================================================

if not defined DEPLOY_HOST (
    echo [copy-host-scripts] FAILED: DEPLOY_HOST is not set. Expected user@host.
    exit /b 1
)

if not defined SSH_OPTS set "SSH_OPTS=-o BatchMode=yes -o ConnectTimeout=15"

echo [copy-host-scripts] Placing the composition and the deployment scripts in %DEPLOY_PATH%...

rem The two directories the release tooling writes into. Created here rather than
rem assumed: the Ansible application role creates them when a host is provisioned
rem with the playbook, but a host reached only by this pipeline must not depend on
rem that. `mkdir -p` makes the command idempotent.
ssh %SSH_OPTS% %DEPLOY_HOST% "mkdir -p %DEPLOY_PATH% %DEPLOY_PATH%/releases %DEPLOY_PATH%/backups"
if errorlevel 1 exit /b 1

rem The composition, then the host-common library, then everything that sources it.
call :copy "deploy\compose.prod.yaml"
if errorlevel 1 exit /b 1
call :copy "deploy\host-common.sh"
if errorlevel 1 exit /b 1
call :copy "deploy\deploy-on-host.sh"
if errorlevel 1 exit /b 1
call :copy "deploy\pre-release.sh"
if errorlevel 1 exit /b 1
call :copy "deploy\release-ledger.sh"
if errorlevel 1 exit /b 1
call :copy "deploy\backup.sh"
if errorlevel 1 exit /b 1
call :copy "deploy\rollback-on-host.sh"
if errorlevel 1 exit /b 1

echo [copy-host-scripts] Done.
endlocal
exit /b 0

:copy
rem %~1 is a path in the repository; %~nx1 is the file name it lands under.
scp %SSH_OPTS% "%~1" "%DEPLOY_HOST%:%DEPLOY_PATH%/%~nx1"
if errorlevel 1 (
    echo [copy-host-scripts] FAILED: could not copy %~1 to %DEPLOY_PATH%.
    exit /b 1
)
exit /b 0
