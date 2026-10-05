package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import java.util.List;
import org.openqa.selenium.By;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class LoginPage {

    private static final By EMAIL = By.id("email");
    private static final By PASSWORD = By.id("password");
    private static final By SUBMIT_BUTTON = By.cssSelector("form button[type='submit']");

    /** Anything the login page can say back: a refusal, or the sign-out confirmation. */
    private static final By FLASH = By.cssSelector(".alert");

    /** The query suffix Spring Security's default failure URL appends to the login page. */
    private static final String REJECTED = "/login?error";

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final String baseUrl;

    public LoginPage(WebDriver driver, String baseUrl) {
        this.driver = driver;
        this.baseUrl = baseUrl;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
    }

    /**
     * Signs in and blocks until the browser has left the {@code /login} URL.
     *
     * <p>The form is always loaded by this method rather than relying on a prior navigation, so the
     * CSRF token embedded in it belongs to the session the browser currently presents.
     *
     * <p>The wait for {@code NOT urlContains("/login")} confirms the POST completed and the session
     * cookie was updated. Without it, a subsequent {@code driver.get()} would race the in-flight
     * redirect.
     *
     * <h2>When the POST does not move off /login</h2>
     *
     * <p>There are two readings of a sign-in stuck on the login URL, and they need opposite
     * handling:
     *
     * <ul>
     *   <li><strong>{@code /login?error}</strong> — authentication ran and rejected the credentials.
     *       This is a real failure, a retry would fail identically, and retrying would disguise it.
     *       Fail now, reporting the page.</li>
     *   <li><strong>bare {@code /login}</strong> — the request was refused <em>before</em>
     *       authentication. For this application that is {@code CsrfFilter}: the submitted token
     *       belonged to a session the browser no longer holds. Spring Security answers a 403 by
     *       <em>forwarding</em> to {@code /access-denied}, and a forward keeps the URL, so no
     *       {@code ?error} is appended and the page is indistinguishable from one never
     *       submitted.</li>
     * </ul>
     *
     * <p>The second reading is recoverable, and only by loading the form again so its token is
     * minted for the current session — so exactly that is retried, once. The root cause of the
     * original occurrence was removed at its source ({@code BaseE2ETest.logout} no longer clears
     * cookies client-side); this retry exists so that a residual instance of the same class of
     * fault costs a retry rather than a red suite. A second failure is reported, never swallowed.
     */
    public void loginAs(String email, String password) {
        submitCredentials(email, password);

        if (leftLoginPage()) {
            return;
        }

        if (credentialsWereRejected()) {
            throw new AssertionError(describeStuckLoginPage(email));
        }

        submitCredentials(email, password);

        if (!leftLoginPage()) {
            throw new AssertionError(describeStuckLoginPage(email));
        }
    }

    /**
     * Loads the form and submits it. Returns once the click is dispatched, not once it lands.
     *
     * <p>The wait for the email field is also this suite's check that it is talking to the portal
     * at all. The sign-in page is the application's front door, and its absence means the browser
     * is somewhere else entirely — a deployment that never started, a stale {@code E2E_BASE_URL},
     * or another service holding the port. That is reported as a page rather than as a missing
     * selector: the first end-to-end run failed here with nothing but
     * {@code no such element: #email}, while the real answer — visible in the captured page source
     * and not in the message — was that the page was Jenkins' own sign-in screen.
     */
    private void submitCredentials(String email, String password) {
        driver.get(baseUrl + "/login");

        try {
            wait.until(ExpectedConditions.visibilityOfElementLocated(EMAIL));
        } catch (TimeoutException notThePortal) {
            throw new AssertionError(
                    "The page at " + driver.getCurrentUrl() + " is not the portal's sign-in page."
                            + "\n  expected: an input with id=\"email\""
                            + "\n  title:    " + driver.getTitle()
                            + "\n  forms:    " + driver.findElements(By.cssSelector("form")).size()
                            + "\n  A title naming another product, or an error page, means"
                            + " E2E_BASE_URL points at something that is not this application.",
                    notThePortal);
        }

        driver.findElement(EMAIL).sendKeys(email);
        driver.findElement(PASSWORD).sendKeys(password);
        driver.findElement(SUBMIT_BUTTON).click();
    }

    /** True once the browser is no longer on the login URL; false if the wait expires. */
    private boolean leftLoginPage() {
        try {
            wait.until(ExpectedConditions.not(ExpectedConditions.urlContains("/login")));
            return true;
        } catch (TimeoutException stillOnLoginPage) {
            return false;
        }
    }

    /** Whether the login page is showing the credential-rejection suffix rather than bare /login. */
    private boolean credentialsWereRejected() {
        return driver.getCurrentUrl().contains(REJECTED);
    }

    /**
     * Everything about the page that separates one way of failing to sign in from another.
     *
     * <p>Written after this failure reported nothing but
     * {@code TimeoutException: ... url to contain "/login"} three runs in a row, with no way to
     * tell which of the causes it was. It is kept so the next occurrence does not cost another
     * round of guessing.
     */
    private String describeStuckLoginPage(String email) {
        return "The sign-in attempt did not leave /login."
                + "\n  attempted as: " + email
                + "\n  url:          " + driver.getCurrentUrl()
                + "\n  title:        " + driver.getTitle()
                + "\n  flash:        " + flashText()
                + "\n  email field:  " + fieldState(EMAIL)
                + "\n  password:     " + fieldState(PASSWORD)
                + "\n  csrf input:   " + csrfState()
                + "\n  forms:        " + driver.findElements(By.cssSelector("form")).size();
    }

    /**
     * Every sentence the page is currently showing, joined — the refusal from a rejected sign-in,
     * and the confirmation from a sign-out, are both {@code .alert} on this page.
     *
     * <p>A plain loop rather than a stream, and the page is read once: this runs while a test is
     * already failing, and a diagnostic that throws would replace the failure it exists to explain.
     */
    private String flashText() {
        List<WebElement> alerts = driver.findElements(FLASH);
        if (alerts.isEmpty()) {
            return "(none)";
        }
        StringBuilder joined = new StringBuilder();
        for (WebElement alert : alerts) {
            if (joined.length() > 0) {
                joined.append(" | ");
            }
            joined.append(alert.getText());
        }
        return joined.toString();
    }

    /**
     * What a field currently holds, as a length rather than the value itself — a password does not
     * belong in a test report ({@code CLAUDE.md} §8).
     */
    private String fieldState(By field) {
        List<WebElement> found = driver.findElements(field);
        if (found.isEmpty()) {
            return "ABSENT";
        }
        String value = found.get(0).getDomProperty("value");
        return value == null || value.isEmpty()
                ? "EMPTY — HTML5 `required` would block the submit"
                : value.length() + " chars";
    }

    /**
     * Whether the form carries the CSRF token the POST depends on — reported, like the fields, as a
     * presence and a length rather than a value.
     *
     * <p>A token that is missing or empty is the decisive reading: {@code CsrfFilter} refuses the
     * POST with a 403 before authentication runs, and Spring Security answers a 403 by
     * <em>forwarding</em> to {@code /access-denied}, which leaves the URL at {@code /login} and
     * appends no {@code ?error}. A rendered-but-unmatched token cannot be told apart here, but its
     * presence rules out the other reading, that the form never carried one.
     */
    private String csrfState() {
        List<WebElement> inputs = driver.findElements(By.cssSelector("form input[name='_csrf']"));
        if (inputs.isEmpty()) {
            return "ABSENT — the POST would be refused with a 403";
        }
        String value = inputs.get(0).getDomProperty("value");
        return value == null || value.isEmpty()
                ? "EMPTY — the POST would be refused with a 403"
                : value.length() + " chars";
    }
}
