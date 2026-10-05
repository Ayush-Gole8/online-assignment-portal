package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * One submission, as its owner sees it.
 *
 * <p>The page is recognised by {@code #submission-detail}, which the page states about itself.
 * This used to be a UUID-shaped URL, and that was the wrong kind of fact in two ways: it describes
 * where the browser is rather than what is on the screen, and it cannot tell "the submission page
 * has rendered" from "a redirect landed somewhere with a UUID in it". A page that says what it is
 * can be waited for on its own terms.
 */
public class SubmissionDetailsPage {

    private static final By PAGE = By.id("submission-detail");

    /**
     * The status badge, read from the state the badge carries rather than the class that colours
     * it.
     *
     * <p>{@code fragments/status} emits {@code data-status} with the enum name —
     * SUBMITTED / APPROVED / REJECTED, the same vocabulary the database stores and the route rules
     * use. The Bootstrap classes beside it (`.badge`, `.status-approved`) are presentation: an
     * assertion written against one of those would fail the day somebody restyled the badge, which
     * is a change no behaviour depends on.
     */
    private static final By STATUS = By.cssSelector("#submission-detail [data-status]");

    private final WebDriverWait wait;

    /**
     * @param driver a browser already on a submission's own page
     * @throws org.openqa.selenium.TimeoutException if this is not that page, naming the marker it
     *                                              waited for
     */
    public SubmissionDetailsPage(WebDriver driver) {
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
        this.wait.until(ExpectedConditions.visibilityOfElementLocated(PAGE));
    }

    public boolean isLoaded() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(PAGE)) != null;
    }

    /** The submission's status, as the enum name the application uses. */
    public String getStatus() {
        WebElement badge = wait.until(ExpectedConditions.visibilityOfElementLocated(STATUS));
        return badge.getAttribute("data-status");
    }
}
