# Defect Demonstration — the quality gate, exercised

**Status:** A **procedure**. Nothing in this document has been observed, because the pipeline has
never run on a Jenkins agent ([ci-quality-gates.md §8](../testing/ci-quality-gates.md)). Every
outcome below is marked **Expected**, and `docs/evidence/README.md` §3 is the rule that makes the
distinction load-bearing: evidence is captured from a real run, never written by hand. When the
drill is first performed, the captured output replaces the *Expected* blocks and the revision is
recorded in the filename.

**Related:** [CI quality gates](../testing/ci-quality-gates.md) · [Jenkinsfile](../../Jenkinsfile) ·
[E2E testing](../testing/e2e-testing.md) · [evidence rules](README.md)

---

## 1. What this drill proves

A quality gate that has never been exercised is a hypothesis. This drill tests the one claim the
whole pipeline rests on:

> A defect that a test can see **cannot reach Docker Build, and therefore cannot be deployed.**

It does so by *planting* a real defect, watching the pipeline stop at the right place, and then
removing it. The defect is never committed — §6 is as much a part of the procedure as §3.

## 2. Why this defect, and not a broken validator

The obvious defect — make `FileValidationService` accept anything — is the wrong instrument. The
unit suite catches it in the **Unit Tests** stage, the pipeline stops at stage 4, and the drill
proves nothing about the browser suite or about the stages beyond it. A defect has to be chosen for
the layer it is invisible to.

The defect below is invisible to every layer except the browser:

| Layer | Why it does not see the defect |
| --- | --- |
| Unit | `FileValidationService` is untouched — it still refuses non-PDF bytes correctly |
| Integration | `SubmissionUploadIT` builds its multipart request with MockMvc. It never renders, or reads, the template that is broken |
| Browser | The browser is the only thing that *uses* the form the template describes |

That gap is the whole argument for keeping a Selenium suite at all
([strategy.md](strategy.md) §3.3): the layers below assemble their own requests, so the one thing
none of them can check is whether the page the application renders actually produces a request the
application can accept.

## 3. The planted defect

**File:** `src/main/resources/templates/submission/upload.html`
**Line:** 75

```html
<!-- before -->
<form id="upload-form" th:action="@{/submissions}" method="post" enctype="multipart/form-data">

<!-- planted defect — the enctype attribute is removed -->
<form id="upload-form" th:action="@{/submissions}" method="post">
```

`enctype="multipart/form-data"` is what makes the browser send the file's **bytes**. Without it the
browser falls back to `application/x-www-form-urlencoded` and sends the file input as an ordinary
text field — the file's *name*, and nothing else. The request arrives with no file part at all, the
server refuses it with `Choose a PDF to upload.`, and the sentence is both technically true and
completely unhelpful.

The template's own header comment already records this, which is why this defect is honest rather
than contrived: the file names the attribute as load-bearing and describes the exact consequence of
removing it.

Nothing else changes. No production logic, no test, no configuration.

## 4. The run

### 4.1 Stages that pass, and why

Stages 1–6 pass. **Expected:** `Build`, `Unit Tests`, `Integration Tests` and `Package` are all
green, because none of them renders the upload form:

```
[Pipeline] stage
[Pipeline] { (Unit Tests)
...
[INFO] Tests run: …, Failures: 0, Errors: 0, Skipped: 0
```

This is the point of the drill, not an inconvenience. A defect that the lower layers *did* catch
would stop the pipeline before the browser suite, and would say nothing about whether the browser
suite is load-bearing or whether the gate holds below Docker Build.

### 4.2 The stage that fails

**Expected:** `Start Test Environment` passes — the JAR starts and `/actuator/health` answers — and
`Selenium E2E` fails, in `StudentWorkflowE2E.studentCanLoginAndSubmitValidPdf`:

```
[INFO] Running com.vit.assignmentportal.e2e.StudentWorkflowE2E
[ERROR] com.vit.assignmentportal.e2e.StudentWorkflowE2E.studentCanLoginAndSubmitValidPdf
        the upload was expected to be stored and answered with the new submission's page,
        but the form refused it: Choose a PDF to upload.
        the browser was on:
          url:            http://localhost:8080/submissions/new
          title:          Upload a submission — Online Assignment Portal
          upload form:    present
          title field:    My First E2E Submission
          file field:     valid-submission….pdf
          visible alerts: (none)
          page says:      Choose a PDF to upload.
[INFO] Tests run: 3, Failures: 1, Errors: 0, Skipped: 0
```

Two details in that output are worth reading rather than skimming:

- The failure names the sentence the form showed, because `SubmissionPage.submitValidPdf` waits for
  the outcome it was promised and reports the outcome it got instead. This is the redesign
  documented in [E2E testing](../testing/e2e-testing.md) §8 — the old wait watched the URL and the
  Bootstrap classes, had no opinion about a refusal, and would have reported a bare ten-second
  timeout against a page it had never described.
- `file field: valid-submission….pdf` shows the input *did* hold a file. The browser put a value on
  it; what it sent was that value as a string. The diagnosis is in the difference, which is exactly
  why the page object reports field values rather than just "the POST did not resolve".

### 4.3 The gate closing

**Expected:** the build result becomes `FAILURE`, and every stage below the gate is skipped:

```
[Pipeline] { (Selenium E2E)
[Pipeline] catchError
...
[Pipeline] } //stage
[Pipeline] stage
[Pipeline] { (Publish Reports and Screenshots)
[Pipeline] junit
Recording test results
[Pipeline] archiveArtifacts
Archiving artifacts
[Pipeline] } //stage
[Pipeline] stage
[Pipeline] { (Docker Build)
Stage "Docker Build" skipped due to when conditional
[Pipeline] } //stage
[Pipeline] stage
[Pipeline] { (Deployment)
Stage "Deployment" skipped due to when conditional
[Pipeline] } //stage
[Pipeline] End of Pipeline
ERROR: … FAILURE
Finished: FAILURE
```

The three lines that matter:

1. **`Publish Reports and Screenshots` still ran.** `catchError` set the result to `FAILURE` without
   aborting the pipeline, so the report explaining the failure is attached to the build that failed.
   A red build with no report is the least useful red build there is.
2. **`Docker Build` was skipped.** Its `when` requires `currentBuild.currentResult == 'SUCCESS'`, and
   the E2E stage already made that false. No image is built.
3. **`Deployment` was skipped**, for the same reason. Nothing is deployed, because nothing was
   released.

`target/e2e-artifacts/studentCanLoginAndSubmitValidPdf/` holds `screenshot.png`,
`page-source.html` and `metadata.txt` for the failed test, and both artifacts and the application
log are attached to the build.

### 4.4 What the screenshot shows

A person opening the screenshot sees the upload form filled in exactly as the test filled it — title,
course code, and a filename on the file input — with `Choose a PDF to upload.` rendered under it.
Every instinct says "but the file is right there". It is the single most useful picture this suite
can produce: the mismatch between what the browser shows and what the server received is the defect,
and no log line shows it as directly.

## 5. The fix, and the rerun

Revert the edit — restore `enctype="multipart/form-data"` on line 75 — and push. Nothing else
changes, so the rerun differs from the failing run in exactly one attribute.

**Expected on the rerun:** `Selenium E2E` green, then:

```
[Pipeline] { (Docker Build)
Stage "Docker Build" skipped due to when conditional
[Pipeline] } //stage
[Pipeline] { (Deployment)
Stage "Deployment" skipped due to when conditional
[Pipeline] } //stage
Finished: SUCCESS
```

Docker Build is skipped here for the *other* reason — no `Dockerfile` exists yet, and the stage is
additionally guarded by `fileExists('Dockerfile')` ([CLAUDE.md §13](../../CLAUDE.md) withholds
Milestone 5 until a build is green). The two skips are distinguishable in the log only by which
`when` failed, which is worth knowing before reading a skip as a gate closure.

**When Milestone 5 lands**, the second rerun is the one that completes the drill: green build →
Docker Build **runs** → Deployment **runs**. The gate is then demonstrated in both directions —
it blocks a bad revision and admits a good one. Until then the drill demonstrates the blocking
direction, which is the one that protects anything.

## 6. Rules for running this drill

- **The defect is never committed.** Plant it in a working tree, run the pipeline, revert it. If it
  reaches a branch, the drill has produced the thing it was meant to catch. `git status` must be
  clean of the defect before the branch is pushed — and the revert is `git checkout -- <file>`, not
  a manual re-edit, so the restore is exact.
- **Run it on a throwaway branch**, e.g. `chore/quality-gate-drill`, never on `main` or `develop`.
- **Capture the real output.** The `Expected` blocks above are replaced by the console the run
  actually produced, saved as
  `docs/evidence/04-e2e-selenium/<UTC-date>-<short-sha>-gate-drill.txt` with the screenshot beside
  it ([evidence rules](README.md) §2).
- **Record the failed run, not only the passing one.** A drill that keeps only the green rerun has
  thrown away the evidence.
- **One defect at a time.** A drill that breaks two things proves neither.

## 7. Known gaps

| Gap | What would close it |
| --- | --- |
| **Never performed.** Every output above is expected, not observed. | Run §3–§5 once on a real agent. |
| **The upstream stages may not pass with the defect planted.** The claim that unit and integration tests do not read `enctype` is a reading of the suite, not a result — no test file mentions `enctype` or `multipart/form-data`, and `SubmissionUploadIT` builds its own multipart request. If a lower layer does catch it, the drill still proves the gate holds; it simply stops at an earlier stage, and a defect closer to the browser must be chosen. | The first run reports which stage stopped. |
| **Docker Build and Deployment always skip today.** | Milestone 5's `Dockerfile` and provisioning, once there is a green build to deploy. |
| **The gate has not been shown to admit a good revision.** | The second half of §5, which needs the Dockerfile. |
