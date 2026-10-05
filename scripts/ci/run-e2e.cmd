@echo off
setlocal

rem ===========================================================================
rem Run the Selenium end-to-end suite against the running test environment.
rem
rem Preconditions
rem   start-database.cmd, start-application.cmd and wait-for-health.cmd have all
rem   succeeded. This script starts nothing: it points the suite at what is
rem   already running and runs it. Keeping start-up and execution as separate
rem   steps is what lets the pipeline attribute a failure to the right thing —
rem   "the environment never came up" and "the application is broken" are
rem   different failures with the same red build otherwise.
rem
rem Which tests run, and why the invocation looks the way it does
rem   The browser suite lives behind the `e2e` profile, so a plain `verify` can
rem   never start a browser (docs/testing/test-strategy.md §4). The profile adds a
rem   Failsafe execution whose id is `e2e-tests`; naming that execution explicitly
rem   runs the browser suite *alone*. A bare `verify -Pe2e` would also re-run the
rem   integration suite, which the Integration Tests stage has already run and
rem   whose report is already published — the same tests twice, attributed to the
rem   wrong stage.
rem
rem   `test-compile` is named because invoking a goal directly does not run the
rem   lifecycle, and Failsafe still needs compiled test classes.
rem
rem Configuration the suite reads (BaseE2ETest)
rem   E2E_BASE_URL       where the application answers; built from APP_PORT below
rem   E2E_STUDENT_EMAIL  the seeded student account   } defaulted in env.cmd, so the
rem   E2E_REVIEWER_EMAIL the seeded reviewer account  } accounts the suite signs in
rem   E2E_PASSWORD       the throwaway password       } as are the ones seeded
rem
rem Environment
rem   APP_PORT is defaulted in env.cmd, next to the port start-application.cmd
rem   binds and wait-for-health.cmd polls.
rem ===========================================================================

call "%~dp0env.cmd"

rem The port comes from env.cmd; an explicit E2E_BASE_URL wins, for pointing the
rem suite at a deployment this pipeline did not start.
if not defined E2E_BASE_URL set "E2E_BASE_URL=http://localhost:%APP_PORT%"

echo [run-e2e] Running the Selenium suite against %E2E_BASE_URL%...

call .\mvnw.cmd -B -Pe2e test-compile failsafe:integration-test@e2e-tests failsafe:verify@e2e-tests

if errorlevel 1 (
    echo [run-e2e] FAILED: the end-to-end suite did not pass.
    echo [run-e2e] A failing test leaves its screenshot and page source under target\e2e-artifacts\.
    exit /b 1
)

echo [run-e2e] The end-to-end suite passed.
endlocal
