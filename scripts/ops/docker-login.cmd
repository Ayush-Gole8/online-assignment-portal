@echo off
setlocal

rem ===========================================================================
rem Authenticate to the registry. Step 2 of the release process.
rem
rem What this does
rem   Logs the agent in to Docker Hub, non-interactively, using the username and
rem   access token that the Jenkins Publish Image stage bound into this process's
rem   environment from the credentials store.
rem
rem Why --password-stdin, and why PowerShell
rem   `docker login --password-stdin` is the only form of `docker login` that does
rem   not put the credential somewhere it can be read:
rem
rem     - `docker login -p <password>` puts it in the process's argument list,
rem       where every other process on the machine can read it from the process
rem       table, and in the Jenkins console log.
rem     - A temporary file can be left behind, can be world-readable, and is one
rem       more copy of a credential that has to be cleaned up correctly.
rem
rem   The pipeline writes the token to docker's standard input, which is a pipe and
rem   never touches the disk. PowerShell is used for that rather than `echo`
rem   because `echo %DOCKERHUB_TOKEN%| docker login ...` would build a command line
rem   containing the token — reintroducing, via the shell, exactly the exposure the
rem   flag exists to avoid. `$env:DOCKERHUB_TOKEN` is a variable reference: the
rem   token is never part of any command text.
rem
rem Why the credential is not read from a file in this repository
rem   CLAUDE.md §7 rule 1 and §8. A credential in the repository is a credential
rem   in every clone, on every machine, for as long as the tag exists. The
rem   identifier in scripts\ops\env.cmd is the NAME of an entry in the Jenkins
rem   credentials store; the value only ever exists in the environment of this
rem   one stage, for the length of the stage.
rem
rem Environment — all three are REQUIRED, and none has a default
rem   DOCKERHUB_USERNAME   bound by withCredentials
rem   DOCKERHUB_TOKEN      bound by withCredentials; a Docker Hub access token,
rem                        not the account password. A token is scoped and
rem                        revocable; a password is neither.
rem   REGISTRY             defaulted in env.cmd (docker.io)
rem ===========================================================================

call "%~dp0env.cmd"

if not defined DOCKERHUB_USERNAME (
    echo [docker-login] FAILED: DOCKERHUB_USERNAME is not set.
    echo [docker-login] This script is run inside a Jenkins withCredentials block. Running it by hand needs the variable exported first.
    exit /b 1
)

if not defined DOCKERHUB_TOKEN (
    echo [docker-login] FAILED: DOCKERHUB_TOKEN is not set.
    echo [docker-login] Expected a Docker Hub access token bound by withCredentials, never the account password.
    exit /b 1
)

echo [docker-login] Logging in to %REGISTRY% as %DOCKERHUB_USERNAME%...

powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:DOCKERHUB_TOKEN | docker login --username $env:DOCKERHUB_USERNAME --password-stdin '%REGISTRY%'; exit $LASTEXITCODE"

if errorlevel 1 (
    echo [docker-login] FAILED: the registry refused the login.
    echo [docker-login] Check that the credential is a Docker Hub access token with write access to the target repository, and that it has not expired.
    exit /b 1
)

echo [docker-login] Logged in.
endlocal
exit /b 0
