@echo off
setlocal

rem ===========================================================================
rem Take, list, and restore the backups of the two persistent volumes.
rem
rem The operator's side of deploy/backup.sh. Everything that touches MySQL or the
rem uploads volume happens ON the host; this script is the way to ask for it
rem without opening an SSH session by hand, and the way to get a dump back onto
rem the agent's disk.
rem
rem What is backed up, and why both halves
rem   ADR-004 splits one submission across two stores: the row is in MySQL, the
rem   bytes are on the uploads volume. A restore that covered one and not the other
rem   would produce a database of submissions whose files are 404s, or a volume of
rem   files no row points at. docs/operations/backup-restore.md §1 records the
rem   asymmetry between the two — MySQL automatically before every release, uploads
rem   on demand — and what that costs.
rem
rem What this deliberately does not do
rem   It does not carry the database password. The dump is taken by a `mysqldump`
rem   running INSIDE the db container, where the password is expanded from that
rem   container's own environment (`MYSQL_PWD`), so it is never an argument to a
rem   command this agent issues and never reaches the Jenkins console (CLAUDE.md §7
rem   rule 1). There is no `-p`, no `--password`, and no `StrictHostKeyChecking=no`
rem   anywhere in this file.
rem
rem   It does not delete, prune, or rotate anything. Retention is a decision for
rem   whoever owns the host's disk, and a script that silently removed the oldest
rem   dump would eventually remove the one an operator needs. backup-restore.md §4
rem   names the gap.
rem
rem Usage
rem   backup-restore.cmd backup-mysql            dump the database on the host
rem   backup-restore.cmd backup-uploads          archive the uploads volume
rem   backup-restore.cmd list                    the backups on the host, newest first
rem   backup-restore.cmd fetch <file>            copy one backup from the host into target\
rem   backup-restore.cmd restore-mysql <file>    load a dump — DESTRUCTIVE, see below
rem   backup-restore.cmd restore-uploads <file>  unpack an archive — DESTRUCTIVE
rem
rem   <file> may be a path on the host (what `list` prints) or the bare name of a
rem   file already fetched into target\; the bare name is resolved to the host's
rem   backups directory first and to target\ second.
rem
rem Environment
rem   DEPLOY_HOST        REQUIRED. user@host.
rem   DEPLOY_PATH        defaulted in env.cmd
rem   RESTORE_CONFIRM    REQUIRED by the restore-* commands, and must equal the
rem                      basename of the file. A restore REPLACES data, so it has to
rem                      be asked for by name — a variable, not a prompt, because
rem                      this also runs in a non-interactive stage.
rem
rem Exit codes
rem   0  the operation succeeded
rem   1  it failed, or the host could not be reached
rem ===========================================================================

call "%~dp0env.cmd"

if not defined DEPLOY_HOST (
    echo [backup-restore] FAILED: DEPLOY_HOST is not set. Expected user@host.
    echo [backup-restore] It has no default on purpose: a backup taken from the wrong host is a backup of the wrong system.
    exit /b 1
)

set "OPERATION=%~1"

if "%OPERATION%"=="" goto :usage

if not exist "target" mkdir "target"

set "SSH_OPTS=-o BatchMode=yes -o ConnectTimeout=15"
rem The remote command prefix. DEPLOY_DIR is passed explicitly because the release
rem tooling is invoked here exactly as the deployment invokes it — same directory,
rem same scripts — so a backup taken by an operator and a backup taken by a release
rem read the same `.env` and write into the same directory.
set "ON_HOST=cd %DEPLOY_PATH% && DEPLOY_DIR=%DEPLOY_PATH%"

if /i "%OPERATION%"=="list"     goto :list
if /i "%OPERATION%"=="backup-mysql"   goto :backup_mysql
if /i "%OPERATION%"=="backup-uploads" goto :backup_uploads
if /i "%OPERATION%"=="fetch"    goto :fetch
if /i "%OPERATION%"=="restore-mysql"   goto :restore_mysql
if /i "%OPERATION%"=="restore-uploads" goto :restore_uploads

echo [backup-restore] FAILED: '%OPERATION%' is not a command.
goto :usage

rem ---------------------------------------------------------------------------
rem The backups on the host
rem ---------------------------------------------------------------------------
:list
echo [backup-restore] Backups in %DEPLOY_PATH%/backups on %DEPLOY_HOST%:
ssh %SSH_OPTS% %DEPLOY_HOST% "%ON_HOST% sh backup.sh list"
if errorlevel 1 goto :failed
endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem Take one
rem ---------------------------------------------------------------------------
:backup_mysql
echo [backup-restore] Dumping the database on %DEPLOY_HOST%...
ssh %SSH_OPTS% %DEPLOY_HOST% "%ON_HOST% sh backup.sh mysql"
if errorlevel 1 goto :failed
echo [backup-restore] The dump is on the host, in %DEPLOY_PATH%/backups.
echo [backup-restore] It is not on this agent. Use `backup-restore.cmd fetch ^<file^>` to bring a copy back.
endlocal
exit /b 0

:backup_uploads
echo [backup-restore] Archiving the uploads volume on %DEPLOY_HOST%...
ssh %SSH_OPTS% %DEPLOY_HOST% "%ON_HOST% sh backup.sh uploads"
if errorlevel 1 goto :failed
echo [backup-restore] The archive is on the host, in %DEPLOY_PATH%/backups.
endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem Copy one back to the agent.
rem
rem Why fetch exists at all: a backup that only ever exists on the machine it
rem protects is not a backup of that machine's failure modes — a lost host takes
rem its dumps with it. The Jenkins agent is not off-site either, but it is a
rem SECOND copy, and it is the copy a restore can be rehearsed from without
rem touching the production host's disk.
rem ---------------------------------------------------------------------------
:fetch
if "%~2"=="" (
    echo [backup-restore] FAILED: fetch needs the file to fetch. Use `backup-restore.cmd list` to see them.
    exit /b 1
)
echo [backup-restore] Copying %~2 from %DEPLOY_HOST%...
scp %SSH_OPTS% "%DEPLOY_HOST%:%~2" "target\%~nx2"
if errorlevel 1 goto :failed
echo [backup-restore] Fetched into target\%~nx2
endlocal
exit /b 0

rem ---------------------------------------------------------------------------
rem Restore. Both of these REPLACE data on the host.
rem ---------------------------------------------------------------------------
:restore_mysql
if "%~2"=="" (
    echo [backup-restore] FAILED: restore-mysql needs the dump to restore.
    exit /b 1
)
call :resolve_backup_path "%~2"
call :restore "restore-mysql" "%RESOLVED_BACKUP%"
endlocal & exit /b %RESTORE_STATUS%

:restore_uploads
if "%~2"=="" (
    echo [backup-restore] FAILED: restore-uploads needs the archive to restore.
    exit /b 1
)
call :resolve_backup_path "%~2"
call :restore "restore-uploads" "%RESOLVED_BACKUP%"
endlocal & exit /b %RESTORE_STATUS%

:restore
rem %~1 is the backup.sh command, %~2 the path on the host.
rem
rem The confirmation gate is passed THROUGH to the host script (which checks it
rem itself) rather than only checked here. The host side is the one that actually
rem overwrites data, so it is the one that must refuse; a check that lived only on
rem the agent would be a gate a hand-run ssh command walks straight past.
if not defined RESTORE_CONFIRM (
    echo [backup-restore] FAILED: RESTORE_CONFIRM is not set.
    echo [backup-restore] A restore REPLACES the current database, or unpacks over the current uploads volume. To run it, set RESTORE_CONFIRM to the basename of the file you are restoring:
    echo [backup-restore]     set RESTORE_CONFIRM=%~nx2
    set "RESTORE_STATUS=1"
    goto :eof
)

echo [backup-restore] Restoring %~2 on %DEPLOY_HOST%. This REPLACES data.
ssh %SSH_OPTS% %DEPLOY_HOST% "%ON_HOST% RESTORE_CONFIRM='%RESTORE_CONFIRM%' sh backup.sh %~1 %~2"
set "RESTORE_STATUS=%ERRORLEVEL%"

if not "%RESTORE_STATUS%"=="0" (
    echo [backup-restore] FAILED: the restore did not complete cleanly.
    echo [backup-restore] The host-side output above says where it stopped. A restore that failed part-way can leave a mixture of the dump and the previous contents, so verify the data before serving traffic — backup-restore.md §5.
    goto :eof
)

echo [backup-restore] Restore complete. The running application was NOT restarted: it holds connections and a connection pool that predate the restored rows.
echo [backup-restore] Restart the stack to be certain of what is serving (docs/operations/backup-restore.md §5).
goto :eof

:resolve_backup_path
rem %~1 is what the operator typed. Prints RESOLVED_BACKUP.
rem
rem A bare name is resolved against the host's backups directory, because that is
rem where `list` prints from. A path is used as given.
rem
rem The test is `%~1` against `%~nx1`: a file name with no directory part is the
rem same string as its own name-plus-extension, so the two differ exactly when a
rem directory was given. (The obvious alternative — a `findstr` against the
rem argument — needs a string piped into it, and cmd has no here-string.)
if "%~1"=="%~nx1" (
    set "RESOLVED_BACKUP=%DEPLOY_PATH%/backups/%~nx1"
) else (
    set "RESOLVED_BACKUP=%~1"
)
goto :eof

rem ---------------------------------------------------------------------------
rem Failures
rem ---------------------------------------------------------------------------
:failed
echo [backup-restore] FAILED: the operation did not complete on the host.
echo [backup-restore] If the host could not be reached at all, check the ssh-agent key and the agent's known_hosts (docs/operations/docker-cd.md §4).
endlocal
exit /b 1

:usage
echo Usage: backup-restore.cmd ^<command^> [arguments]
echo        backup-mysql ^| backup-uploads ^| list
echo        fetch ^<file^>
echo        restore-mysql ^<file^> ^| restore-uploads ^<file^>
echo.
echo Restores require RESTORE_CONFIRM set to the file's basename.
exit /b 1
