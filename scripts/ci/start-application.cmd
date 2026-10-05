@echo off
setlocal

rem ===========================================================================
rem Start the application under test.
rem
rem What this does
rem   Launches the packaged Spring Boot JAR as an independent process, with its
rem   stdout and stderr redirected to files under target\, and records the
rem   process id so stop-environment.cmd can stop exactly this process and
rem   nothing else on the agent.
rem
rem Why the packaged JAR and not `mvn spring-boot:run`
rem   The artefact that is deployed is the JAR. Running it here is what makes the
rem   end-to-end stage an assembly test of the thing that ships, started the way
rem   an operator starts it, rather than a test of a Maven plugin's launcher.
rem
rem Why the `dev` profile and APP_SEED_ENABLED
rem   The suite signs in as a student and a reviewer, and a real deployment does
rem   not ship those accounts. DevelopmentUserSeeder creates them, and it is
rem   guarded three times over — @Profile("dev"), app.seed.enabled=true, and one
rem   variable per account with no default anywhere in the code. Activating it is
rem   therefore explicit and confined to this disposable environment; the accounts
rem   exist only in the database this stage creates and destroys. The same
rem   mechanism a developer uses locally is the one used here, which is why the
rem   suite does not need a backdoor (docs/testing/e2e-testing.md §7).
rem
rem The account credentials below are the throwaway values BaseE2ETest uses by
rem default, so the seeded accounts and the suite agree without either reading
rem the other's mind. They are not secrets: this database is created by
rem start-database.cmd and destroyed by stop-environment.cmd in the same run
rem (SECURITY.md §5, docs/testing/test-data.md).
rem
rem Outputs
rem   target\ci-application.log      stdout
rem   target\ci-application.err.log  stderr
rem   target\ci-application.pid      the JVM's process id
rem ===========================================================================

if not defined JAR_PATH set "JAR_PATH=target\online-assignment-portal-0.0.1-SNAPSHOT.jar"
if not defined LOG_PATH set "LOG_PATH=target\ci-application.log"
if not defined ERR_PATH set "ERR_PATH=target\ci-application.err.log"
if not defined PID_PATH set "PID_PATH=target\ci-application.pid"

if exist "%PID_PATH%" del /q "%PID_PATH%"

if not exist "%JAR_PATH%" (
    echo [start-application] FAILED: %JAR_PATH% does not exist. Run the Package stage first.
    exit /b 1
)

rem Resolve the JDK. JAVA_HOME is the JDK root, not its bin directory, and the
rem enforcer plugin fails the build on the wrong JDK, so an unset JAVA_HOME is
rem reported here rather than surfacing later as a confusing startup error.
if defined JAVA_HOME (
    set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_EXE=java"
)

rem ---------------------------------------------------------------------------
rem Configuration handed to the application — application.yml reads these names.
rem The port and the database coordinates come from env.cmd, so they are the same
rem ones wait-for-health.cmd polls and run-e2e.cmd points the suite at.
rem ---------------------------------------------------------------------------
call "%~dp0env.cmd"

set "SPRING_PROFILES_ACTIVE=dev"
set "APP_SEED_ENABLED=true"
set "APP_SEED_STUDENT_EMAIL=%E2E_STUDENT_EMAIL%"
set "APP_SEED_STUDENT_PASSWORD=%E2E_PASSWORD%"
set "APP_SEED_REVIEWER_EMAIL=%E2E_REVIEWER_EMAIL%"
set "APP_SEED_REVIEWER_PASSWORD=%E2E_PASSWORD%"

set "DB_URL=jdbc:mysql://127.0.0.1:%DB_PORT%/%DB_NAME%"
if not defined UPLOAD_STORAGE_PATH set "UPLOAD_STORAGE_PATH=target\ci-uploads"

if not exist "%UPLOAD_STORAGE_PATH%" mkdir "%UPLOAD_STORAGE_PATH%"

echo [start-application] Starting %JAR_PATH% on port %APP_PORT% against %DB_URL%...
echo [start-application] stdout -> %LOG_PATH%
echo [start-application] stderr -> %ERR_PATH%

powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference = 'Stop'; $process = Start-Process -FilePath '%JAVA_EXE%' -ArgumentList '-jar','%JAR_PATH%' -RedirectStandardOutput '%LOG_PATH%' -RedirectStandardError '%ERR_PATH%' -PassThru -WindowStyle Hidden; $process.Id | Out-File -Encoding ascii -NoNewline '%PID_PATH%'; Write-Host ('[start-application] java pid ' + $process.Id)"

if errorlevel 1 (
    echo [start-application] FAILED: the process could not be started.
    exit /b 1
)

endlocal
