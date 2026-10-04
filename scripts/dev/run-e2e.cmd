@echo off
setlocal

echo ========================================================
echo Running End-to-End (E2E) Test Suite (Local Dev)
echo ========================================================
echo.
echo Note: Ensure Docker Desktop is running before starting.
echo Testcontainers will automatically provision MySQL.
echo.

call .\mvnw.cmd verify -Pe2e

if %ERRORLEVEL% equ 0 (
    echo.
    echo E2E tests completed successfully.
) else (
    echo.
    echo E2E tests failed. Check target/e2e-artifacts/ for screenshots.
    exit /b %ERRORLEVEL%
)

endlocal
