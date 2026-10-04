# End-to-End (E2E) Testing Strategy

**Status:** Selenium automated E2E tests are established.

## 1. Overview
The end-to-end tests exercise the Online Assignment Portal through a real web browser (Google Chrome) against a fully booted application and a containerized database. This mirrors the user experience precisely, confirming that HTML templates, controllers, the service layer, security, and the database correctly interact in critical user journeys.

## 2. Technologies
- **Selenium 4**: Drives the browser. It includes **Selenium Manager**, which automatically downloads the matching ChromeDriver. Driver binaries are deliberately excluded from this repository.
- **Spring Boot Test**: Boots the application on a random available port.
- **Testcontainers**: Provisions a real MySQL 8.4 database for each E2E test suite execution. No local database installation is necessary, but Docker must be running.

## 3. Local Execution Flow
E2E tests are grouped under the `e2e` Maven profile to avoid accidental lengthy executions during unit or integration phases.

Instead of typing lengthy shell commands, use the provided scripts:
- **Development**: Run `.\scripts\dev\run-e2e.cmd`
- **Continuous Integration**: Run `.\scripts\ci\run-e2e.cmd` (adds the `-B` batch mode flag)

Alternatively, run via Maven directly:
`.\mvnw.cmd verify -Pe2e`

## 4. Test Environment Reproducibility
The application state is hermetic per test suite execution:
- Testcontainers ensures an isolated, migrated MySQL container is initialized at startup.
- Accounts are seeded automatically before tests execute.
- E2E tests use `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` to guarantee no port conflicts.

## 5. Failure Artifacts
When an E2E test fails, a JUnit 5 extension (`ScreenshotExtension`) automatically captures a full-page screenshot of the browser at the exact moment of failure. 
These artifacts are written to `target/e2e-artifacts/`. 

## 6. Page Objects & Selectors
Tests must not interact with `WebDriver` directly to locate elements. 
Instead, we use the **Page Object Model** (e.g. `LoginPage`, `StudentDashboardPage`).

### Selector Guidelines
- **Use stable identifiers**: Bind to semantic IDs (`id="email"`) or `name` attributes.
- **Do not use brittle XPaths**: Avoid `//div/span[2]/a`. If you must use XPath, use resilient matching (e.g., `//a[contains(@href, '/submissions')]` or `//a[text()='Submit']`).
- **CSS Selectors**: Prefer robust CSS over XPath when selecting elements by class or structure.

### Synchronization
- **Use explicit waits**: Use `WebDriverWait` combined with `ExpectedConditions` (e.g., `visibilityOfElementLocated`).
- **NEVER use `Thread.sleep()`**: Hardcoded sleeps are fundamentally flaky and needlessly slow the pipeline.

## 7. No Test Backdoors
The application source code (`src/main`) does not contain endpoints, inputs, or headers designed solely to bypass workflows for Selenium. E2E tests sign in through `/login` exactly as a user does. The production security boundary remains impenetrable.

## 8. Waiting for a form submission to resolve

Every state-changing page in the portal is a POST that answers `303 See Other` and lands on a GET. Waiting for that round trip correctly is the single hardest part of these tests, and the two ways it goes wrong are both silent.

### 8.1 Do not wait for the page to become "stale"

The tempting condition is `ExpectedConditions.stalenessOf(element)` — "the document was replaced, so the element I found is stale". It does not work, and it fails on **both** outcomes:

- `stalenessOf` catches `NoSuchElementException` and returns `false`. Once the document has been replaced, the old element is not stale but *absent*, so the condition never becomes true and the wait burns its whole timeout against a page that finished loading seconds ago.
- On a **refusal**, the browser is redirected back to the same form, which renders a brand-new element with the same id. There was never a moment when the thing being watched had gone away.

Wait for the **outcome** instead — the URL a success redirects to, or the message a refusal renders. `SubmissionPage.fillAndSubmit` is the reference implementation.

### 8.2 Know where a refusal is rendered

A refusal is rendered in one of two places, and which one depends on whether it names a form field:

| Refusal | Rendered as | Example |
| --- | --- | --- |
| About the request as a whole (no field named) | `.alert-danger`, above the form | `Choose a PDF to upload.` |
| About a single input (field named) | `.invalid-feedback.d-block`, under that input | `That file is not a valid PDF.` |

The upload form deliberately leaves the top-level alert empty when a field is named, so the sentence is not shown twice. This matters more than it looks: **every file-validation refusal is the second shape**, because `SubmissionService.submit` throws the validator's message with the field `"file"`. An assertion that waits only for `.alert-danger` will time out on exactly the case the hostile-input test exists to exercise. Both are matched by `SubmissionPage.REFUSAL_MESSAGE`.

### 8.3 The click is not the navigation

`WebElement.click()` returns once the click has been *dispatched*, not once the browser has committed the new document. A lookup issued in the next statement can therefore be evaluated against the page the click started from. Where a page object clicks a link that navigates, it waits for the destination URL before returning — see `ReviewerQueuePage.openSubmission`. `ReviewerDetailsPage` likewise waits for the detail page's status badge in its constructor, so that "the reason field is missing" means the submission is already decided rather than "the page never loaded".

