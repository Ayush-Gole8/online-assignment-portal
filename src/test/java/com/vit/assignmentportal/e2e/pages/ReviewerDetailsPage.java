package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class ReviewerDetailsPage {

    private static final By APPROVE_BUTTON = By.cssSelector("button[value='APPROVED']");
    private static final By REJECT_BUTTON = By.cssSelector("button[value='REJECTED']");
    private static final By REASON_FIELD = By.id("note");

    /**
     * The status badge, rendered by {@code fragments/status} at the top of every submission page.
     *
     * <p>Used as the "this page is the one I think it is" marker. Waiting for it here means a bad
     * navigation fails in the constructor and names the page, instead of failing later on the
     * reason field — which is absent both when the page never loaded and when the submission has
     * already been decided, and so cannot distinguish the two.
     */
    private static final By STATUS_BADGE = By.cssSelector(".badge.status");

    /**
     * The alert the decision POST renders when it answers.
     *
     * <p>Both outcomes flash one: a recorded decision flashes {@code decided} (rendered as
     * {@code .alert-success}) and a refusal flashes {@code error} (rendered as
     * {@code .alert-danger}). Neither is present on the page before the decision is posted, so
     * the appearance of either is a real transition and not a coincidental match.
     */
    private static final By OUTCOME_ALERT = By.cssSelector(".alert-success, .alert-danger");

    private final WebDriver driver;
    private final WebDriverWait wait;

    /**
     * @param driver a browser already on the reviewer's view of one submission, that is
     *               {@code /review/submissions/<uuid>}
     */
    public ReviewerDetailsPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
        this.wait.until(ExpectedConditions.visibilityOfElementLocated(STATUS_BADGE));
    }

    /** Approves the submission, blocking until the decision has been recorded. */
    public void approve() {
        decide(APPROVE_BUTTON);
    }

    /** Rejects the submission with a reason, blocking until the decision has been recorded. */
    public void reject(String reason) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(REASON_FIELD)).sendKeys(reason);
        decide(REJECT_BUTTON);
    }

    /**
     * Clicks the decision button and waits for the redirect to land.
     *
     * <p>Both buttons submit the same form; they differ only in the {@code outcome} value they
     * send. The POST answers {@code 303 See Other} back to this page, which is why the wait is
     * for an alert rather than for a URL change — the URL is the same either way.
     *
     * <p>Waiting matters beyond tidiness: the callers log out and sign in again as soon as this
     * returns. Without the wait, that next navigation can be started while this one is still in
     * flight, and the two race for a browser that will honour only one of them.
     */
    private void decide(By button) {
        wait.until(ExpectedConditions.elementToBeClickable(button)).click();
        wait.until(ignored -> !driver.findElements(OUTCOME_ALERT).isEmpty());
    }
}
