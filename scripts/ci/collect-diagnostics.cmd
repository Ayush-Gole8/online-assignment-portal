@echo off
setlocal

rem ===========================================================================
rem Collect diagnostics from a pipeline run.
rem
rem What this does
rem   Copies everything a person would need to explain a failed run into one
rem   directory, target\ci-diagnostics\, so the pipeline can archive a single
rem   path instead of a list of globs that has to be kept in step with the tests.
rem
rem Why this runs on failure *and* on success
rem   The Jenkinsfile calls it from `post { always }`. A green run's diagnostics
rem   are still worth keeping: "the application started in 4 seconds and the
rem   suite took 90" is how a regression in startup time is noticed, and it is
rem   evidence for docs/evidence/. Collecting only on failure means the baseline
rem   is never captured.
rem
rem What is collected, and what is deliberately not
rem   The application's logs, the browser suite's failure artifacts, and the
rem   Surefire/Failsafe XML. The uploaded artefacts themselves are not: they are
rem   the application's data, they are large, and nothing in a pipeline failure
rem   report needs to read them.
rem
rem Failure policy
rem   This script never fails the build. It runs in a `post` block, where a second
rem   failure would replace the one the pipeline is already reporting. A missing
rem   log is reported and the script continues.
rem ===========================================================================

if not defined DIAGNOSTICS_DIR set "DIAGNOSTICS_DIR=target\ci-diagnostics"

echo [collect-diagnostics] Collecting into %DIAGNOSTICS_DIR%...

if not exist "%DIAGNOSTICS_DIR%" mkdir "%DIAGNOSTICS_DIR%"

call :copyIfPresent "target\ci-application.log"     "%DIAGNOSTICS_DIR%\application.log"
call :copyIfPresent "target\ci-application.err.log" "%DIAGNOSTICS_DIR%\application.err.log"
call :copyIfPresent "target\ci-application.pid"     "%DIAGNOSTICS_DIR%\application.pid"

rem The browser suite's failure artifacts. One directory per failed test, each
rem holding screenshot.png, page-source.html and metadata.txt (docs/testing/e2e-testing.md §5).
if exist "target\e2e-artifacts" (
    robocopy "target\e2e-artifacts" "%DIAGNOSTICS_DIR%\e2e-artifacts" /E /NFL /NDL /NJH /NJS /NP >nul
    echo [collect-diagnostics] Copied target\e2e-artifacts.
) else (
    rem No parentheses in this sentence: an unescaped `(` or `)` inside a parenthesised
    rem cmd block ends the block early, and the failure that produces is a syntax error at a
    rem line that looks perfectly ordinary.
    echo [collect-diagnostics] No target\e2e-artifacts - the suite wrote none, or it did not run.
)

rem The machine-readable test reports. Jenkins renders these from the workspace;
rem the copies here are what makes docs/evidence/ able to hold the same evidence
rem without reaching into a build that has already been discarded.
call :copyTree "target\surefire-reports" "%DIAGNOSTICS_DIR%\surefire-reports"
call :copyTree "target\failsafe-reports" "%DIAGNOSTICS_DIR%\failsafe-reports"

echo [collect-diagnostics] Done.
endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem :copyIfPresent <source> <destination>
rem ---------------------------------------------------------------------------
:copyIfPresent
if exist "%~1" (
    copy /y "%~1" "%~2" >nul
    echo [collect-diagnostics] Copied %~1
) else (
    echo [collect-diagnostics] Absent, skipped: %~1
)
exit /b 0

rem ---------------------------------------------------------------------------
rem :copyTree <source-directory> <destination-directory>
rem ---------------------------------------------------------------------------
:copyTree
if exist "%~1" (
    robocopy "%~1" "%~2" /E /NFL /NDL /NJH /NJS /NP >nul
    rem robocopy reports success as exit code 1 ("files copied"), which cmd reads as
    rem an error. Reset it so this script's own exit code stays meaningful.
    ver >nul
    echo [collect-diagnostics] Copied %~1
) else (
    echo [collect-diagnostics] Absent, skipped: %~1
)
exit /b 0
