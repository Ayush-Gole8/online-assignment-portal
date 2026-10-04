@echo off
setlocal

echo ========================================================
echo Running End-to-End (E2E) Test Suite (CI Pipeline)
echo ========================================================
echo.

call .\mvnw.cmd -B verify -Pe2e

if %ERRORLEVEL% equ 0 (
    echo.
    echo E2E tests completed successfully.
) else (
    echo.
    echo E2E tests failed.
    exit /b %ERRORLEVEL%
)

endlocal
