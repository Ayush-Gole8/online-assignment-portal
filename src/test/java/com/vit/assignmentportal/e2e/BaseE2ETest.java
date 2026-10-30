package com.vit.assignmentportal.e2e;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The browser suite's base class: one Chrome per test, pointed at an application that is already
 * running.
 *
 * <h2>Against a started deployment, not an in-process one</h2>
 *
 * <p>This class used to carry {@code @SpringBootTest(webEnvironment = RANDOM_PORT)} and extend
 * {@code MySqlIntegrationTest}, so every E2E class booted the application inside the test JVM on a
 * random port and started its own MySQL container. That made the suite convenient to run and it
 * tested the wrong thing: the artefact that ships is the packaged JAR, started by an operator
 * against an external database, and an assembly test that never starts the assembly proves nothing
 * about it.
 *
 * <p>The suite now drives whatever {@code E2E_BASE_URL} names. The pipeline starts a database and
 * the packaged application, waits for {@code /actuator/health}, then runs these tests against that
 * deployment — the same artefact, the same startup path, the same configuration mechanism the
 * delivery pipeline will use. What is being proven is the whole assembly, which is the only thing
 * this layer exists for ({@code docs/testing/strategy.md} §3.3).
 *
 * <h2>The environment contract</h2>
 *
 * <p>Everything the suite needs is an environment variable, so the harness carries no environment
 * of its own:
 *
 * <table>
 *   <caption>Variables read by this class</caption>
 *   <tr><th>Variable</th><th>Meaning</th><th>Default</th></tr>
 *   <tr><td>{@code E2E_BASE_URL}</td><td>Where the running application answers</td>
 *       <td>{@code http://localhost:8081}</td></tr>
 *   <tr><td>{@code E2E_STUDENT_EMAIL}</td><td>The student account to sign in as</td>
 *       <td>{@value #DEFAULT_STUDENT_EMAIL}</td></tr>
 *   <tr><td>{@code E2E_REVIEWER_EMAIL}</td><td>The reviewer account to sign in as</td>
 *       <td>{@value #DEFAULT_REVIEWER_EMAIL}</td></tr>
 *   <tr><td>{@code E2E_PASSWORD}</td><td>The password for both accounts</td>
 *       <td>a throwaway value, see below</td></tr>
 * </table>
 *
 * <p>The accounts are not created here. Creating them was the in-process harness's job, and with an
 * external application it is the environment's: the scripts under {@code scripts/ci} start the
 * application with the {@code dev} profile and {@code APP_SEED_ENABLED=true}, and
 * {@code DevelopmentUserSeeder} creates one account per role from its own environment variables. A
 * browser suite that could create its own accounts would also be a browser suite that could create
 * accounts in an environment it does not own, which is exactly the backdoor
 * {@code docs/testing/e2e-testing.md} §7 rules out.
 *
 * <p>The default password is a throwaway value for a disposable environment, not a credential:
 * {@code SECURITY.md} §5 and {@code docs/testing/test-data.md} both state that a test credential
 * for a database that is created and destroyed by the run is not a secret, and no environment that
 * holds real data ever sets the seeding variables that would use it.
 *
 * <h2>Waits</h2>
 *
 * <p>Implicit waits are deliberately NOT set on the driver. Mixing implicit and explicit waits
 * produces undefined behaviour: when {@code WebDriverWait} polls for a condition, the implicit
 * wait causes each {@code findElement} call to block for its own timeout before throwing the
 * {@code NoSuchElementException} that the expected condition relies on catching, which causes the
 * outer explicit wait to observe wildly inflated polling intervals and time out early.
 */
@ExtendWith(E2EFailureArtifacts.class)
public abstract class BaseE2ETest {

    /**
     * Where the application answers when nothing says otherwise.
     *
     * <p>8081, not Spring Boot's usual 8080 — and the same value {@code scripts/ci/env.cmd}
     * defaults to, so a run through the scripts and a bare {@code mvn -Pe2e} target the same
     * deployment. Jenkins claims 8080 on the machine that runs this pipeline, and a default
     * pointed there is how the suite once drove Jenkins' own sign-in page and reported
     * {@code no such element: #email} against a page titled "Sign in - Jenkins"
     * ({@code docs/testing/ci-quality-gates.md} §7).
     */
    private static final String DEFAULT_BASE_URL = "http://localhost:8081";

    private static final String DEFAULT_STUDENT_EMAIL = "student@example.com";
    private static final String DEFAULT_REVIEWER_EMAIL = "reviewer@example.com";

    /** A throwaway credential for a disposable environment — see the class Javadoc. */
    private static final String DEFAULT_PASSWORD = "password123";

    protected static final String PASSWORD = resolve("E2E_PASSWORD", DEFAULT_PASSWORD);
    protected static final String STUDENT_EMAIL = resolve("E2E_STUDENT_EMAIL", DEFAULT_STUDENT_EMAIL);
    protected static final String REVIEWER_EMAIL = resolve("E2E_REVIEWER_EMAIL", DEFAULT_REVIEWER_EMAIL);

    /**
     * The sign-out control: a POST form in the navigation bar ({@code fragments/navbar.html}).
     *
     * <p>Selected by the action it posts to rather than by its label, so it cannot be confused
     * with the decision form or the upload form on the pages that carry one as well — those post
     * elsewhere, and the navigation bar's form is the only one that posts to {@code /logout}. The
     * action is matched by substring rather than exactly, so that a context path would not matter.
     */
    private static final By SIGN_OUT =
            By.cssSelector("form[action*='/logout'] button[type='submit']");

    /**
     * Default explicit wait timeout, used throughout all page objects.
     *
     * <p>Ten seconds is measured against a local deployment. A CI agent that is slower than the
     * developer's machine does not need a longer wait here: the pipeline's
     * {@code wait-for-health} script is what absorbs a slow start, and by the time a test is
     * running the application is already serving requests.
     */
    public static final Duration WAIT = Duration.ofSeconds(10);

    protected WebDriver driver;

    @BeforeEach
    void setUp() {
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        driver = new ChromeDriver(options);
        // Do NOT set implicitlyWait here — see class-level Javadoc.
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    public WebDriver getDriver() {
        return driver;
    }

    /**
     * The deployment under test.
     *
     * <p>Read once, at class-initialisation time, so that every test in a run drives the same
     * target even if the variable changed halfway through — which cannot happen in a JVM, but the
     * constant makes the intent obvious rather than incidental.
     */
    protected String getBaseUrl() {
        return resolve("E2E_BASE_URL", DEFAULT_BASE_URL);
    }

    /**
     * Signs out through the portal's own control, and blocks until the sign-in page is the
     * current document.
     *
     * <h2>Why not just delete the cookie</h2>
     *
     * <p>This method used to clear the browser's session cookie
     * ({@code driver.manage().deleteAllCookies()}) and navigate to {@code /login}. That looked
     * equivalent to signing out and was not, in two ways that together made the <em>next</em>
     * sign-in fail intermittently:
     *
     * <ul>
     *   <li><strong>It is a client-side fiction.</strong> The server's session survives it —
     *       still valid, still authenticated. Nothing was signed out, and the suite was not
     *       exercising sign-out at all. The portal's sign-out is a CSRF-protected
     *       {@code POST /logout} that invalidates the session server-side and clears the
     *       authentication ({@code SecurityConfiguration}, ADR-005); no other request ends a
     *       session.</li>
     *   <li><strong>It races.</strong> The cookie deletion is asynchronous. A navigation issued
     *       immediately afterwards can have its {@code Set-Cookie} wiped by a deletion that lands
     *       a moment late, which leaves the sign-in form carrying a CSRF token minted for one
     *       session while the browser presents none. The POST is then refused by {@code CsrfFilter}
     *       with a 403, and Spring Security answers a 403 by <em>forwarding</em> to
     *       {@code /access-denied} — a forward keeps the URL, so the browser sits at bare
     *       {@code /login} with no {@code ?error} to explain it, exactly as though the form had
     *       never been submitted.</li>
     * </ul>
     *
     * <p>Signing out through the form removes both. The browser is told to drop the cookie by the
     * server's own response header, in the same exchange that destroys the session, so there is no
     * window in which the two disagree.
     *
     * <h2>Why the navigation comes first</h2>
     *
     * <p>The sign-out control lives in the navigation bar, which only the signed-in layout
     * renders. Navigating to {@code /} — a page every authenticated role may reach, with the
     * navigation on it — means this method does not care which page its caller left the browser on.
     * A caller that reaches it while anonymous would be redirected to {@code /login} and the wait
     * would fail naming the sign-out control, which is the honest report of "there was nobody to
     * sign out".
     */
    protected void logout() {
        WebElement signOut = null;
        try {
            signOut = new WebDriverWait(driver, Duration.ofSeconds(2))
                    .until(ExpectedConditions.presenceOfElementLocated(SIGN_OUT));
        } catch (Exception e) {
            driver.get(getBaseUrl() + "/");
            signOut = new WebDriverWait(driver, WAIT)
                    .until(ExpectedConditions.presenceOfElementLocated(SIGN_OUT));
        }
        try {
            ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].closest('form').submit();", signOut);
        } catch (Exception ignored) {
            signOut.click();
        }
        // logoutSuccessUrl("/login?logout"), so the URL is the signal that the sign-out completed.
        new WebDriverWait(driver, WAIT)
                .until(ExpectedConditions.urlContains("/login"));
        new WebDriverWait(driver, WAIT)
                .until(ExpectedConditions.visibilityOfElementLocated(By.id("email")));
    }

    protected File createDummyPdf(String prefix) throws Exception {
        Path tempFile = Files.createTempFile(prefix, ".pdf");
        // Minimal but structurally valid PDF header that passes the magic-byte check.
        String content = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n";
        Files.writeString(tempFile, content);
        return tempFile.toFile();
    }

    /**
     * Creates a file whose name ends in {@code .pdf} but whose content is plain text,
     * so Chrome's {@code accept="application/pdf,.pdf"} filter passes it but the server's
     * magic-byte check refuses it with "That file is not a valid PDF."
     */
    protected File createDummyTextFile(String prefix) throws Exception {
        Path tempFile = Files.createTempFile(prefix, ".pdf");
        Files.writeString(tempFile, "This is not a PDF");
        return tempFile.toFile();
    }

    /** An environment variable, or the given default when it is unset or blank. */
    private static String resolve(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : value;
    }
}
