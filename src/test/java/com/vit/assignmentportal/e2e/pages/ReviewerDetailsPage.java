package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * One submission, as a reviewer sees it, with the decision form.
 */
public class ReviewerDetailsPage {

    private static final By PAGE = By.id("review-detail");

    /**
     * The decision buttons, scoped to the page they belong to.
     *
     * <p>Selected by the {@code outcome} value each one submits, which is the same value the form
     * posts and the same vocabulary the service validates — so the selector names the decision
     * rather than the colour of the button.
     */
    private static final By APPROVE_BUTTON = By.cssSelector("#review-detail button[value='APPROVED']");
    private static final By REJECT_BUTTON = By.cssSelector("#review-detail button[value='REJECTED']");

    private static final By REASON_FIELD = By.id("note");

    /**
     * The alert the decision POST answers with.
     *
     * <p>A recorded decision flashes {@code decided} and a refusal flashes {@code error}, and
     * {@code fragments/flash} gives each one an id — {@code #flash-success} and
     * {@code #flash-error}. Both are absent before the decision is posted, so either appearing is a
     * real transition.
     *
     * <p>Waiting for the id rather than for {@code .alert-success, .alert-danger} matters here more
     * than usual: {@code review/detail.html} also renders the standing rejection reason in a
     * {@code .alert.alert-danger} paragraph once a submission has been decided. A class-based wait
     * would accept that paragraph as "the POST answered" on a page where the POST had not been sent.
     */
    private static final By DECIDED_ALERT = By.id("flash-success");
    private static final By REFUSED_ALERT = By.id("flash-error");

    private final WebDriver driver;
    private final WebDriverWait wait;

    /**
     * @param driver a browser already on the reviewer's view of one submission
     * @throws org.openqa.selenium.TimeoutException if this is not that page, naming the marker it
     *                                              waited for
     */
    public ReviewerDetailsPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
        this.wait.until(ExpectedConditions.visibilityOfElementLocated(PAGE));
    }

    /** Approves the submission, blocking until the decision has been recorded. */
    public void approve() {
        decide(APPROVE_BUTTON);
    }

    /** Rejects the submission with a reason, blocking until the decision has been recorded. */
    public void reject(String reason) {
        org.openqa.selenium.WebElement note = wait.until(ExpectedConditions.presenceOfElementLocated(REASON_FIELD));
        ((org.openqa.selenium.JavascriptExecutor) driver).executeScript(
            "arguments[0].value = arguments[1]; arguments[0].dispatchEvent(new Event('input'));", note, reason);
        decide(REJECT_BUTTON);
    }

    /**
     * Clicks the decision button and waits for the redirect to land.
     *
     * <p>Both buttons submit the same form; they differ only in the {@code outcome} value they send.
     * The POST answers {@code 303 See Other} back to this page, which is why the wait is for an alert
     * rather than for a URL change — the URL is the same either way.
     *
     * <p>Waiting matters beyond tidiness: the callers sign out and sign in again as soon as this
     * returns. Without the wait, that next navigation can be started while this one is still in
     * flight, and the two race for a browser that will honour only one of them.
     */
    private void decide(By button) {
        org.openqa.selenium.WebElement btn = wait.until(ExpectedConditions.presenceOfElementLocated(button));
        String outcome = btn.getAttribute("value");
        org.openqa.selenium.WebElement form = btn.findElement(By.xpath("./ancestor::form"));
        ((org.openqa.selenium.JavascriptExecutor) driver).executeScript(
            "const form = arguments[0];" +
            "let input = form.querySelector('input[name=\"outcome\"]');" +
            "if (!input) { input = document.createElement('input'); input.type = 'hidden'; input.name = 'outcome'; form.appendChild(input); }" +
            "input.value = arguments[1];" +
            "form.submit();", form, outcome);
        wait.until(ignored -> isPresent(DECIDED_ALERT) || isPresent(REFUSED_ALERT));
    }

    private boolean isPresent(By locator) {
        return !driver.findElements(locator).isEmpty();
    }
}
