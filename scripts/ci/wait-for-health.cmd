@echo off
setlocal

rem ===========================================================================
rem Wait until the application under test is serving.
rem
rem What this does
rem   Polls the Actuator health endpoint until it answers 200, or until the
rem   deadline passes. On timeout it prints the application's own log tail, so
rem   the failure that explains the timeout is in the stage that timed out rather
rem   than in a file nobody opened.
rem
rem Why the body is read, and not merely the status code
rem   This script originally accepted any 200 from the port, on the reasoning that
rem   Spring Boot answers 200 when healthy and 503 when it is not. That reasoning
rem   has a hole, and the first end-to-end run fell straight through it: *another*
rem   service answering 200 on the same port satisfies the gate. Jenkins was
rem   listening on 8080, the gate passed against Jenkins, and the browser suite
rem   drove Jenkins' sign-in page. A readiness gate that the wrong server can
rem   satisfy is a false green, which is worse than a red build — it reports
rem   success for a deployment that was never tested.
rem
rem   So the response must be a Spring health document reporting UP. The endpoint
rem   publishes `{"status":"UP"}`; `management.endpoint.health.show-details: never`
rem   hides the components, not the status. `Invoke-RestMethod` parses the JSON, so
rem   `.status` is a property lookup rather than a string match, and for an HTML
rem   page served by some other service `.status` is empty, which is not "UP".
rem
rem Why a wait at all
rem   `java -jar` returns as soon as the JVM starts; the context, Flyway and the
rem   seeder come later. A suite that starts before the port is listening fails in
rem   the browser with a connection error, which looks like a broken page rather
rem   than an application that had not finished starting. This is the standard
rem   readiness gate, and it is why the pipeline has a "Start Test Environment"
rem   stage at all.
rem
rem Environment
rem   APP_PORT is defaulted in env.cmd, next to the port start-application.cmd
rem   binds — the same value, from the same file.
rem   HEALTH_TIMEOUT_SECONDS=120   HEALTH_POLL_SECONDS=2
rem ===========================================================================

call "%~dp0env.cmd"
if not defined HEALTH_TIMEOUT_SECONDS set "HEALTH_TIMEOUT_SECONDS=120"
if not defined HEALTH_POLL_SECONDS set "HEALTH_POLL_SECONDS=2"

set "HEALTH_URL=http://localhost:%APP_PORT%/actuator/health"

echo [wait-for-health] Waiting up to %HEALTH_TIMEOUT_SECONDS%s for %HEALTH_URL%...

powershell -NoProfile -ExecutionPolicy Bypass -Command "$deadline = (Get-Date).AddSeconds(%HEALTH_TIMEOUT_SECONDS%); while ((Get-Date) -lt $deadline) { try { $health = Invoke-RestMethod -Uri '%HEALTH_URL%' -TimeoutSec 5; if ($health.status -eq 'UP') { Write-Host '[wait-for-health] The application is UP.'; exit 0 } } catch { } Start-Sleep -Seconds %HEALTH_POLL_SECONDS% }; exit 1"

if errorlevel 1 (
    echo [wait-for-health] FAILED: %HEALTH_URL% did not report a Spring health document with status UP within %HEALTH_TIMEOUT_SECONDS%s.
    echo [wait-for-health] If another service owns port %APP_PORT%, the application never bound it and its log will look healthy.
    echo [wait-for-health] Check what is listening before reading the application log.
    if exist "target\ci-application.log" (
        echo [wait-for-health] Last 40 lines of target\ci-application.log:
        powershell -NoProfile -Command "Get-Content 'target\ci-application.log' -Tail 40"
    )
    if exist "target\ci-application.err.log" (
        echo [wait-for-health] Last 40 lines of target\ci-application.err.log:
        powershell -NoProfile -Command "Get-Content 'target\ci-application.err.log' -Tail 40"
    )
    exit /b 1
)

endlocal
