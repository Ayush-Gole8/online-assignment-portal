@echo off
setlocal

rem ===========================================================================
rem Run the Selenium end-to-end suite locally.
rem
rem What this does
rem   Builds the packaged JAR, then runs the same environment lifecycle the
rem   pipeline runs — start the database, start the application, wait for health,
rem   run the suite, tear everything down. It is a thin orchestrator on purpose:
rem   every step it performs is scripts\ci\*.cmd, the same script the Jenkinsfile
rem   calls, so a local run and a CI run cannot drift into testing different
rem   things. If this passes locally and CI fails, the difference is the machine,
rem   not the procedure.
rem
rem Why the application is started rather than booted in-process
rem   The suite drives a deployment, not a Spring context it created. That is what
rem   makes it an assembly test of the artefact that ships
rem   (docs/testing/e2e-testing.md §2).
rem
rem Preconditions
rem   Docker Desktop is running (the database is a container), and a JDK 21 is on
rem   JAVA_HOME.
rem ===========================================================================

set "CI_SCRIPTS=%~dp0..\ci"
set "PORTAL_EXIT=0"

echo ========================================================
echo End-to-end suite - local run
echo ========================================================
echo.

echo [1/5] Building the packaged JAR...
call .\mvnw.cmd -B package -DskipTests
if errorlevel 1 (
    echo Failed to build the JAR. Nothing was started.
    exit /b 1
)
echo.

echo [2/5] Starting the database...
call "%CI_SCRIPTS%\start-database.cmd"
if errorlevel 1 goto :finish
echo.

echo [3/5] Starting the application...
call "%CI_SCRIPTS%\start-application.cmd"
if errorlevel 1 goto :finish
echo.

echo [4/5] Waiting for the application to report healthy...
call "%CI_SCRIPTS%\wait-for-health.cmd"
if errorlevel 1 goto :finish
echo.

echo [5/5] Running the browser suite...
call "%CI_SCRIPTS%\run-e2e.cmd"
set "PORTAL_EXIT=%ERRORLEVEL%"

:finish
echo.
echo Tearing the environment down...
call "%CI_SCRIPTS%\stop-environment.cmd"
call "%CI_SCRIPTS%\collect-diagnostics.cmd"

echo.
if "%PORTAL_EXIT%" equ "0" (
    echo End-to-end suite completed successfully.
) else (
    echo End-to-end suite failed.
    echo See target\e2e-artifacts\ for a failed test's screenshot and page source,
    echo and target\ci-diagnostics\ for the application log.
)

endlocal & exit /b %PORTAL_EXIT%
