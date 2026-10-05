@echo off

rem ===========================================================================
rem Shared defaults for the CI environment scripts.
rem
rem How to use it
rem   From a script that has already called `setlocal`:
rem
rem       call "%~dp0env.cmd"
rem
rem   Each variable is assigned only when it is not already defined, so an
rem   operator or the pipeline can override any of them from the outside. This
rem   file deliberately does NOT call `setlocal` of its own: the values have to
rem   outlive the call and be visible to the calling script.
rem
rem Why one file instead of a default in each script
rem   Four scripts need the same port and the same database coordinates, and
rem   Jenkins runs every `bat` step in its own cmd.exe, so nothing is inherited
rem   from one stage to the next. A default copied into four files is four
rem   defaults that will disagree the first time one of them is edited — and
rem   `start-application.cmd` binding one port while `wait-for-health.cmd` polls
rem   another is a failure that reads as "the application never started".
rem
rem Why APP_PORT is not 8080
rem   Jenkins listens on 8080 by default, and this pipeline runs on the Jenkins
rem   host. A test deployment that also claims 8080 cannot bind — and the failure
rem   is worse than a crash, because `wait-for-health.cmd` polls the port, Jenkins
rem   answers it, the readiness gate passes against the wrong server, and the
rem   browser suite drives Jenkins' own sign-in page. That is exactly what the
rem   first end-to-end run did: it reported `no such element: #email` against a
rem   page whose title was "Sign in - Jenkins". 8081 keeps the deployment and the
rem   CI server off the same socket.
rem
rem The database credentials below
rem   Throwaway values for a database this pipeline creates, uses and destroys
rem   within a single run, published on the loopback interface only. They are not
rem   secrets and must never be reused anywhere that holds real data
rem   (SECURITY.md §5, docs/testing/test-data.md).
rem ===========================================================================

if not defined DB_NAME     set "DB_NAME=assignment_portal"
if not defined DB_USERNAME set "DB_USERNAME=assignment_portal"
if not defined DB_PORT     set "DB_PORT=3306"
if not defined DB_PASSWORD set "DB_PASSWORD=ci-throwaway-db-password"
if not defined DB_ROOT_PASSWORD set "DB_ROOT_PASSWORD=ci-throwaway-root-password"

if not defined APP_PORT set "APP_PORT=8081"

rem The accounts DevelopmentUserSeeder creates and the password the suite signs
rem in with. Shared so that start-application.cmd, which seeds them, and
rem run-e2e.cmd, which signs in as them, cannot drift apart — they used to carry
rem the same three literals in two files.
if not defined E2E_STUDENT_EMAIL  set "E2E_STUDENT_EMAIL=student@example.com"
if not defined E2E_REVIEWER_EMAIL set "E2E_REVIEWER_EMAIL=reviewer@example.com"
if not defined E2E_PASSWORD       set "E2E_PASSWORD=password123"
