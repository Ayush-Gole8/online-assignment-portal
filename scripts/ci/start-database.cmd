@echo off
setlocal

rem ===========================================================================
rem Start the CI database.
rem
rem What this does
rem   Brings up MySQL 8.4 from deploy/docker-compose.yml and blocks until its
rem   own healthcheck reports healthy. The same image tag the integration tests
rem   and production use, so the E2E environment is not a different engine.
rem
rem Why compose rather than Testcontainers
rem   The integration tests own their container: it is created and destroyed
rem   inside their JVM and nothing outside them needs it. The end-to-end suite
rem   is the opposite — it drives an application that is already running and has
rem   no Spring context of its own, so *something* has to own the database for
rem   the duration of the stage. That is this script, and stop-environment.cmd
rem   is its other half.
rem
rem Credentials
rem   The compose file refuses to start without DB_PASSWORD and DB_ROOT_PASSWORD
rem   (${VAR:?...}), which is deliberate: a database that starts with an empty
rem   password is worse than one that does not start. The values below are
rem   throwaway credentials for a database that this pipeline creates, uses and
rem   destroys in the same run, published on the loopback interface only. They
rem   are not secrets and must never be reused anywhere that holds real data
rem   (SECURITY.md §5, docs/testing/test-data.md). A caller that has real
rem   credentials sets these variables and this script does not touch them.
rem
rem Environment
rem   Every value this script needs — DB_NAME, DB_USERNAME, DB_PORT, DB_PASSWORD,
rem   DB_ROOT_PASSWORD — is defaulted in scripts\ci\env.cmd, so this script and
rem   the application it feeds cannot disagree about which database to use.
rem ===========================================================================

call "%~dp0env.cmd"

echo [start-database] Starting MySQL 8.4 (database %DB_NAME%, port %DB_PORT%)...

docker compose -f deploy\docker-compose.yml up -d --wait db

if errorlevel 1 (
    echo [start-database] FAILED: the database did not become healthy.
    echo [start-database] Container state:
    docker compose -f deploy\docker-compose.yml ps
    echo [start-database] Recent logs:
    docker compose -f deploy\docker-compose.yml logs --tail 50 db
    exit /b 1
)

echo [start-database] Database is healthy.
endlocal
