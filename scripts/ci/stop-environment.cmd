@echo off
setlocal

rem ===========================================================================
rem Stop the test environment: the application and the database.
rem
rem What this does
rem   1. Stops the JVM that start-application.cmd launched, by the process id it
rem      recorded — and, failing that, by matching the JAR name in the command
rem      line of any java.exe, so a PID file that was never written (the process
rem      crashed at startup) does not leave a stray JVM holding the application's
rem      port for the next build on this agent.
rem   2. Destroys the database and its volume (`down -v`).
rem
rem Why `down -v` and not `down`
rem   The volume is the point. A CI database that survives the run would carry one
rem   build's submissions into the next, and the browser suite's fixed submission
rem   titles would then match more than one row. Destroying the volume is what
rem   makes a run independent of every run before it. The named volume is
rem   assignment-portal-mysql-data (compose.yaml), created and removed by this
rem   pair of scripts and by nothing else in CI.
rem
rem Failure policy
rem   This runs from `post { always }`, where a failure would replace the one the
rem   pipeline is already reporting, so every step is best-effort and the script
rem   always exits 0. A container that would not stop is Jenkins's problem to
rem   report on the next run, not a second reason for a red build.
rem
rem Environment (optional)
rem   APP_JAR_TOKEN=online-assignment-portal   the JAR name fragment used to find a stray JVM
rem ===========================================================================

if not defined PID_PATH set "PID_PATH=target\ci-application.pid"
if not defined APP_JAR_TOKEN set "APP_JAR_TOKEN=online-assignment-portal"

echo [stop-environment] Stopping the application...

powershell -NoProfile -ExecutionPolicy Bypass -Command "$pidFile = '%PID_PATH%'; $stopped = @(); if (Test-Path $pidFile) { $appPid = (Get-Content $pidFile -Raw).Trim(); if ($appPid -match '^\d+$') { try { Stop-Process -Id ([int]$appPid) -Force -ErrorAction Stop; $stopped += $appPid } catch { } } }; Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -like '*%APP_JAR_TOKEN%*' } | ForEach-Object { try { Stop-Process -Id $_.ProcessId -Force -ErrorAction Stop; $stopped += $_.ProcessId } catch { } }; if ($stopped.Count -gt 0) { Write-Host ('[stop-environment] Stopped process ids: ' + ($stopped -join ', ')) } else { Write-Host '[stop-environment] No application process was running.' }"

echo [stop-environment] Destroying the database and its volume...

rem compose.yaml also declares the uploads volume. `down -v` removes that one too,
rem which is correct for CI and is why this script is not the one to reach for
rem when clearing up after a container stack you wanted to keep (docker.md §5).
docker compose -f compose.yaml down -v --remove-orphans

if errorlevel 1 (
    echo [stop-environment] WARNING: the database did not shut down cleanly. Continuing.
)

echo [stop-environment] Done.
endlocal
exit /b 0
