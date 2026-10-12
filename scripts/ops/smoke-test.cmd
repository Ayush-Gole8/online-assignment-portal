@echo off
setlocal

rem ===========================================================================
rem Post-deployment smoke test. Step 10 of the release process.
rem
rem What this does
rem   Checks that a deployment is serving, that it is the release that was meant
rem   to be deployed, and that it renders and guards. Seven HTTP requests: health,
rem   the version the application reports, the environment it reports, the sign-in
rem   page, the anonymous redirect, the sign-in form's CSRF token, and the
rem   anonymous refusal on the /submissions business route.
rem
rem Where the checks live, and why not here
rem   In smoke-test.ps1 beside this file. The checks build and read JSON and
rem   HTML responses, and writing that as a cmd script means either a fistful of
rem   findstr against JSON — which breaks on whitespace — or one unreadable
rem   PowerShell line inside a .cmd, which is how scripts stop being reviewed.
rem
rem Why this runs from the Jenkins agent and not on the deployment host
rem   Because "the container can reach itself" is not the claim a deployment has
rem   to establish. The host already waited for the container's own healthcheck
rem   before this runs; this asks the same endpoint from another machine, over the
rem   network, which is the question a user is actually asking.
rem
rem Environment
rem   DEPLOY_BASE_URL   REQUIRED. Origin of the deployment, no trailing slash.
rem   RELEASE_VERSION   REQUIRED. The version that was just deployed.
rem   DEPLOY_ENV        REQUIRED. staging or production.
rem   SMOKE_TIMEOUT_SECONDS  defaulted in env.cmd
rem ===========================================================================

call "%~dp0env.cmd"

if not defined DEPLOY_BASE_URL (
    echo [smoke-test] FAILED: DEPLOY_BASE_URL is not set.
    echo [smoke-test] Expected the origin of the deployment, such as http://portal.example.com:8080
    exit /b 1
)

if not defined RELEASE_VERSION (
    echo [smoke-test] FAILED: RELEASE_VERSION is not set. The smoke test checks that the deployment is THIS release, so it has to be told which release that is.
    exit /b 1
)

if not defined DEPLOY_ENV (
    echo [smoke-test] FAILED: DEPLOY_ENV is not set. Expected staging or production.
    exit /b 1
)

if not exist "target" mkdir "target"

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0smoke-test.ps1" ^
    -BaseUrl "%DEPLOY_BASE_URL%" ^
    -ExpectedVersion "%RELEASE_VERSION%" ^
    -ExpectedEnvironment "%DEPLOY_ENV%" ^
    -TimeoutSeconds %SMOKE_TIMEOUT_SECONDS% ^
    -ReportPath "target\smoke-test.txt"

if errorlevel 1 (
    echo [smoke-test] FAILED: the deployment is answering but is not what was released.
    exit /b 1
)

echo [smoke-test] Passed.
endlocal
exit /b 0
