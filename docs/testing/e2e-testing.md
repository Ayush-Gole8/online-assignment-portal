# End-to-End (E2E) Testing Strategy

**Status:** Selenium automated E2E tests are established, and run against a started deployment in
their own Maven profile.
**Related:** [CI quality gates](ci-quality-gates.md) · [test strategy](strategy.md) ·
[CLAUDE.md §6](../../CLAUDE.md)

## 1. Overview

The end-to-end tests exercise the Online Assignment Portal through a real web browser (Google
Chrome) against a running deployment and a containerised database. They confirm that HTML
templates, controllers, the service layer, security, and the database interact correctly in the
critical user journeys — the one thing no lower layer can prove, because the lower layers put the
request together themselves and a browser does not.

## 2. Technologies

- **Selenium 4**: drives the browser. It includes **Selenium Manager**, which downloads the driver
  matching the installed Chrome on first use. No driver binary is committed, and none is downloaded
  by the build.
- **The packaged application, already running.** The suite does *not* boot the application. It
  drives whatever `E2E_BASE_URL` names, and something else — `scripts\dev\run-e2e.cmd` locally, the
  pipeline's *Start Test Environment* stage in CI — starts the packaged JAR and waits for
  `/actuator/health` before the suite runs.
- **MySQL 8.4 in a container**, started by `scripts\ci\start-database.cmd` from
  `deploy/docker-compose.yml`. Docker must be running.

### Why the suite no longer starts its own application

It used to. `BaseE2ETest` carried `@SpringBootTest(webEnvironment = RANDOM_PORT)` and extended
`MySqlIntegrationTest`, so every E2E class booted the application inside the test JVM on a random
port, with its own MySQL container.

That was convenient and it tested the wrong thing. The artefact that ships is the packaged JAR,
started by an operator against an external database; an assembly test that never starts the
assembly proves nothing about it, and the one class of defect this layer exists to catch — the page
the application renders not producing a request the application can accept — is a property of the
deployed application, not of a Spring context the test built for itself.

So the suite now drives a deployment. Everything it needs arrives as an environment variable, and
it starts nothing:

| Variable | Meaning | Default |
| --- | --- | --- |
| `E2E_BASE_URL` | Where the running application answers | `http://localhost:8081` |
| `E2E_STUDENT_EMAIL` | The student account to sign in as | `student@example.com` |
| `E2E_REVIEWER_EMAIL` | The reviewer account to sign in as | `reviewer@example.com` |
| `E2E_PASSWORD` | The password for both | a throwaway value |

The default port is **8081, not 8080**, because Jenkins claims 8080 on the machine that runs this
pipeline. It is not a cosmetic choice: with the default pointed at 8080 the suite once drove Jenkins'
own sign-in page and failed with `no such element: #email`, and the readiness gate passed against
Jenkins too. `scripts\ci\env.cmd` holds the value and the reasoning, and `E2E_BASE_URL` overrides it
for any deployment. The suite also now fails naming the page it actually found, so a wrong target is
one line rather than a captured DOM.

The accounts are not created by the suite either. Creating them was the in-process harness's job,
and with an external application it is the environment's: the scripts start the application with the
`dev` profile and `APP_SEED_ENABLED=true`, and `DevelopmentUserSeeder` creates one account per role
from its own environment variables. A browser suite that could create its own accounts would also be
a browser suite that could create accounts in an environment it does not own, which is exactly the
backdoor §7 rules out.

The default password is a throwaway for a disposable environment, not a credential: the database is
created by `start-database.cmd` and destroyed by `stop-environment.cmd` in the same run
(`SECURITY.md` §5, [test-data.md](test-data.md)). No environment holding real data ever sets the
seeding variables that would use it.

## 3. Commands and the expected lifecycle

**A plain `clean verify` must never start a browser.** The browser suite is grouped under the `e2e`
Maven profile, and that is only half of it — the failsafe configuration also refuses the category by
tag, so a browser test misnamed `SomethingIT` still cannot run under `verify`. The two guards are
deliberately independent:

| Guard | Where | What it stops |
| --- | --- | --- |
| Class-name convention | Failsafe's default includes, `**/IT*.java` and `**/*IT.java` | An `*E2E` class is not selected by a plain `verify` at all |
| JUnit tag | `excludedGroups` = `e2e` | A browser test named like an integration test is still withheld |

The profile is the only way to run the browser suite, and naming it is the acknowledgement that a
browser and a live server are wanted.

### What each command runs

```bash
# Unit phase only — no Spring context, no Docker. Seconds.
./mvnw test

# Everything except the browser suite. Docker required (Testcontainers MySQL 8.4).
# This is the command a change must leave green.
./mvnw clean verify

# The integration suite AND the browser suite. The profile adds an execution
# rather than replacing one. Requires a running deployment — see below.
./mvnw clean verify -Pe2e
```

On Windows:

```powershell
.\mvnw.cmd clean verify
.\mvnw.cmd clean verify -Pe2e
```

### The browser suite needs an environment, so it has a script

`-Pe2e` selects the suite; it does not start anything for it to run against. Because the suite now
drives an external deployment (§2), the reproducible command is the script that creates and destroys
that deployment:

```powershell
.\scripts\dev\run-e2e.cmd
```

It builds the JAR, starts the database, starts the application, waits for health, runs the suite,
and tears everything down whether or not the suite passed. Every step is one of the six scripts in
`scripts\ci\`, so a local run is the same procedure a CI run performs
([ci-quality-gates.md](ci-quality-gates.md) §3).

Running Maven directly is still supported, against an environment someone else started:

```powershell
.\mvnw.cmd -B -Pe2e test-compile failsafe:integration-test@e2e-tests failsafe:verify@e2e-tests
```

The `@e2e-tests` names the profile's Failsafe execution, which runs the browser suite **alone**. A
bare `verify -Pe2e` also re-runs the integration suite — the same tests, now attributed to whichever
stage invoked them.

### The lifecycle of a browser run, in order

1. `package -DskipTests` — the executable JAR, the artefact the suite will drive.
2. `start-database.cmd` — MySQL 8.4 up and healthy.
3. `start-application.cmd` — the JAR, detached, logging to `target\ci-application.log`.
4. `wait-for-health.cmd` — `/actuator/health` answers `200`.
5. `test-compile` — the suite's classes.
6. **Failsafe** runs the `e2e-tests` execution over `*E2E`, selecting the `e2e` tag and excluding
   `integration`. Each class starts a headless Chrome and drives `E2E_BASE_URL`.
7. `verify` — Failsafe fails the build if any test in that execution failed.
8. `collect-diagnostics.cmd`, then `stop-environment.cmd` — evidence kept, environment destroyed.

## 4. Test environment reproducibility

- A run creates its own database and destroys it. `start-database.cmd` brings up MySQL 8.4 from
  `deploy/docker-compose.yml`; `stop-environment.cmd` runs `down -v`, which drops the volume. A run
  therefore does not depend on the run before it, which matters because the suite creates real rows
  and their titles are only unique within a run.
- Accounts are seeded by the application at startup, from the environment, and only if absent, so
  restarting the application changes nothing.
- The application binds `APP_PORT` (8081 in the CI scripts; `application.yml` still defaults to 8080
  for the product) rather than a random port, because it is started as a deployment rather than
  booted by the test. The CI value must not be 8080, because Jenkins claims that port on the host
  running the pipeline and a deployment that cannot bind it lets the readiness gate pass against
  Jenkins — `scripts\ci\env.cmd` records the reasoning. `disableConcurrentBuilds()` in the
  `Jenkinsfile` is what keeps two builds from racing for that port, the container name, and the
  volume — the trade the external environment makes in exchange for testing the real artefact.
- Uploaded bytes are written under `target\ci-uploads`, inside the workspace, so `cleanWs()` reclaims
  them and nothing leaks between builds.
- The suite does not require a specific port or a specific account: `E2E_BASE_URL` and the
  `E2E_*` variables let it be pointed at any deployment, which is also what makes it usable against
  a staging environment later.

## 5. Failure artifacts

A failing browser test writes what the browser was looking at into

```
target/e2e-artifacts/<test-method-name>/
    screenshot.png      the visible viewport at the moment of failure
    page-source.html    the document as the server sent it
    metadata.txt        test id, timestamp, URL, title, the failure and its first stack frames
```

Nothing is written when a test passes, and nothing is committed: `target/` is git-ignored. These are
evidence for a human reading a failed run, not fixtures.

### Why the failure has to be captured before the test ends

`E2EFailureArtifacts` is a JUnit `InvocationInterceptor`, not a `TestWatcher`, and the difference is
the entire reason this works.

It began as a `TestWatcher`, and it never produced a single file. JUnit invokes
`TestWatcher.testFailed` **after** the class's `@AfterEach` methods, and
`BaseE2ETest.tearDown` quits the driver. Every capture therefore ran against a dead session and
ended in

```
Failed to save screenshot: Session ID is null. Using WebDriver after calling quit()?
```

which sat in the Failsafe report's `system-err` output — written, printed, and unnoticed, because a
diagnostic that fails is silent in exactly the way a diagnostic that was never written is silent.
That is how `target/e2e-artifacts/` came to be empty on every failing run.

`interceptTestMethod` wraps the test method itself. The failure passes through the interceptor
before any `@AfterEach` runs, so the driver is still alive and the page is still on screen. The
ordering is not a convention anyone has to remember; it is the position of the call.

The capture is also deliberately unable to do harm: each artifact is written independently behind
its own `try`, a failure to write one is reported on `stderr` and does not stop the others, and the
original throwable is always rethrown unchanged. A diagnostic must never replace the failure it
exists to explain.

## 6. Page objects and selectors

Tests do not touch `WebDriver` to find elements. Each page has a Page Object
(`LoginPage`, `StudentDashboardPage`, `SubmissionPage`, `SubmissionDetailsPage`,
`ReviewerQueuePage`, `ReviewerDetailsPage`) that owns the selectors for that page and exposes
intent-level methods.

### Wait for state the application names

The rule that matters most: **wait for something the application says about itself, not for
something that happens to be true of the current markup.**

The upload page object used to decide that a POST had resolved by testing a regular expression
against the URL (`/submissions/<uuid>`) or by looking for the Bootstrap classes
`.alert-danger` / `.invalid-feedback.d-block`. Neither is a statement about what the server decided.
They are statements about how the page is built, and when the POST produced a third thing — a 403
forwarded to `/access-denied`, a redirect back to the sign-in page, an error page — the condition
had no opinion about it. The test reported a ten-second timeout against a page it had never
described, and the artifact that would have described it could not be written (§5).

So the pages now state what they are, and the tests read that:

| Marker | On | Means |
| --- | --- | --- |
| `#upload-form` | `submission/upload.html` | the upload form is rendered |
| `#submission-detail` | `submission/detail.html` | a stored submission's own page |
| `#file-error` | `fragments/validation.html` | a refusal about the file input |
| `#flash-error` | `fragments/flash.html` | a refusal about the request as a whole |
| `#flash-success` | `fragments/flash.html` | a recorded decision, or a completed upload |
| `#student-dashboard`, `#review-queue`, `#review-detail` | the respective pages | which page this is |
| `[data-status]` | `fragments/status.html` | the outcome, as `SUBMITTED`/`APPROVED`/`REJECTED` |

`data-status` carries the same value as the badge's text, and it exists because the badge repeats
once per table row and so cannot carry a fixed id. The text is a rendering; the attribute is the
state; the test reads the state.

### Selector guidance

- **Prefer semantic ids and `data-` attributes.** `#file-error` and `[data-status]` are names the
  application defines for its own concepts.
- **Do not select on styling.** A class that exists to colour something (`.badge`,
  `.alert-danger`) can be renamed by a restyling that changes no behaviour, and a test that breaks
  when it is renamed was testing the stylesheet.
- **Scope a selector to the page it belongs to.** `#upload-form button[type='submit']`, not
  `main form button[type='submit']` — the latter is a statement about the layout fragment, which
  this page does not own.
- **Do not use brittle XPaths.** Avoid `//div/span[2]/a`. Where XPath is needed, match resiliently —
  `//tr[.//a[normalize-space()='<title>']]` finds a row by its visible text.
- **Locate by attribute, not by index,** so that adding a row to a page cannot silently move an
  assertion onto a different element.

### Synchronization

- **Explicit waits only**, via `WebDriverWait` and `ExpectedConditions`.
- **Never `Thread.sleep()`.** There is not one in this suite: a fixed sleep is a guess about how
  long a machine takes, and it is wrong on the slow machine and wasteful on the fast one.
- **Implicit waits are deliberately not set** on the driver. Mixing implicit and explicit waits is
  undefined behaviour: the implicit wait makes each `findElement` block for its own timeout before
  throwing the `NoSuchElementException` that the expected condition depends on catching, so the
  outer wait observes wildly inflated polling intervals and gives up early.

## 7. No test backdoors

The application source (`src/main`) contains no endpoint, parameter, or header that exists to make
Selenium's job easier. The E2E tests sign in through `/login` exactly as a person does, upload
through the real form, and decide through the real buttons. The production security boundary is the
same one the tests cross.

Two things follow from that, and both are load-bearing:

- The suite therefore also covers the CSRF token on every state-changing form, because it posts the
  forms a browser posts.
- A browser-side convenience attribute (`accept`, `required`, `maxlength` on the upload form) is
  never the thing under test. `FileValidationService` validates the bytes on the server, and the
  hostile-input test exists to prove the client-side filter is not the control (`CLAUDE.md` §7
  rule 4).

## 8. Waiting for a form submission to resolve

Every state-changing page in the portal is a POST that answers `303 See Other` and lands on a GET.
Waiting for that round trip correctly is the hardest part of these tests, and it fails silently when
it is written the obvious way.

### 8.1 Do not wait for the page to become "stale"

The tempting condition is `ExpectedConditions.stalenessOf(element)` — "the document was replaced, so
the element I found is stale". It does not work, and it fails on **both** outcomes:

- `stalenessOf` catches `NoSuchElementException` and returns `false`. Once the document has been
  replaced, the old element is not stale but *absent*, so the condition never becomes true and the
  wait burns its whole timeout against a page that finished loading seconds ago.
- On a **refusal**, the browser is redirected back to the same form, which renders a brand-new
  element with the same id. There was never a moment when the thing being watched had gone away.

Wait for the **outcome** instead. `SubmissionPage.awaitResolution` waits for one of the three states
the form can reach — the stored submission's page, a refusal under the file input, or a refusal above
the form — and reports what the page actually was if none of them arrives.

### 8.2 Know where a refusal is rendered

A refusal appears in one of two places, and which one depends on whether it names a form field:

| Refusal | Rendered as | Marker | Example |
| --- | --- | --- | --- |
| About the request as a whole | above the form, by `fragments/flash` | `#flash-error` | `Choose a PDF to upload.` |
| About a single input | under that input, by `fragments/validation` | `#<field>-error` | `That file is not a valid PDF.` |

The upload form deliberately leaves the top-level alert empty when a field is named, so the sentence
is not shown twice. This matters more than it looks: **every file-validation refusal is the second
shape**, because `SubmissionService.submit` throws the validator's message with the field `"file"`.
A wait that knew only the first shape would time out on exactly the case the hostile-input test
exists to exercise.

`SubmissionPage` therefore has two methods rather than one, because the two journeys have different
evidence:

- `submitValidPdf(...)` waits for `#submission-detail` and returns a `SubmissionDetailsPage`. If the
  upload was refused instead, it fails naming the sentence the form showed.
- `submitPdfExpectingRefusal(...)` waits for the refusal, returns its sentence, and fails if the
  upload was accepted instead.

Neither can pass on the other's outcome, and neither caller has to decide afterwards which thing it
got.

### 8.3 The click is not the navigation

`WebElement.click()` returns once the click has been *dispatched*, not once the browser has
committed the new document. A lookup issued in the next statement can therefore be evaluated against
the page the click started from. Where a page object clicks a link that navigates, it waits for the
destination before returning — `ReviewerQueuePage.openSubmission` waits for `#review-detail`.
`ReviewerDetailsPage` likewise waits for `#review-detail` in its constructor, and
`SubmissionDetailsPage` for `#submission-detail`, so that "the reason field is missing" means the
submission is already decided rather than "the page never loaded".

### 8.4 Sign out by signing out

`BaseE2ETest.logout()` used to clear the browser's cookie (`driver.manage().deleteAllCookies()`) and
navigate to `/login`. It looked equivalent to signing out. It is not, and it made the *next* sign-in
fail intermittently — once as a ten-second timeout naming the login URL, in a run where another
reviewer test had signed in perfectly well.

The cookie deletion is asynchronous, so a navigation issued immediately afterwards can have its
`Set-Cookie` wiped by a deletion that lands a moment late. The sign-in form is then rendered with a
CSRF token minted for one session while the browser presents none, `CsrfFilter` refuses the POST
with a 403, and Spring Security answers a 403 by *forwarding* to `/access-denied`. **A forward keeps
the URL**, so the browser sits at bare `/login` with no `?error` — indistinguishable from a form
that was never submitted.

Two things are worth stating rather than rediscovering:

- **Read the URL on a stuck sign-in.** `/login?error` means the credentials were rejected —
  authentication ran. Bare `/login` means the request was refused *before* authentication, which for
  this application is CSRF.
- **A client-side logout is not a logout.** The server session survived it, still valid and still
  authenticated, so the suite was not exercising sign-out at all. The portal's sign-out is a
  CSRF-protected `POST /logout` that invalidates the session server-side and clears the
  authentication (`SecurityConfiguration`, ADR-005); nothing else ends a session.

`logout()` therefore navigates to `/` and clicks the navigation bar's own sign-out control, waiting
for `/login?logout`. That removes the race, and it means the suite covers a security boundary it
previously only pretended to cross.

`LoginPage.loginAs` then handles a stuck sign-in by its two readings rather than by one timeout.
`?error` means the credentials were rejected: it fails immediately, because a retry would fail the
same way and would disguise a real problem. Bare `/login` means the request was refused before
authentication, which for this application is CSRF — so the form is loaded once more, so its token
is minted against the session the browser actually holds, and submitted again. Only that second
reading is retried, once, and a second failure is reported rather than swallowed. When a sign-in
still fails to move off `/login`, the page's state is reported — URL, title, any alert, which fields
hold anything, and whether the form carries a CSRF token — instead of a bare timeout.

## 9. In the pipeline

The suite is a stage in the Jenkins pipeline, and it is the stage that gates everything downstream.
`docs/testing/ci-quality-gates.md` describes the stage order, the six reusable scripts that start and
stop the environment, what is published when a test fails, and where the quality gate is expressed.

Two things about that integration belong here, because they are properties of this suite rather than
of the pipeline:

- **The suite is what makes the gate meaningful.** The stages below it — Docker Build and Deployment —
  are reached only when it passes, so a defect the browser can see cannot be released. That claim is
  exercised end to end by the defect drill in
  `docs/evidence/defect-demonstration.md`, which plants a defect that every layer *except* this one
  is blind to.
- **A failed run leaves its evidence behind.** `target/e2e-artifacts/` (§5) is archived with the
  build, so the screenshot and page source of a failure survive the workspace being cleaned.
