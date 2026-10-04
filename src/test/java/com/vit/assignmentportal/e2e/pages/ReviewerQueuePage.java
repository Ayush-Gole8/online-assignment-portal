package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class ReviewerQueuePage {

    /**
     * A reviewer's view of one submission: {@code /review/submissions/<uuid>}.
     *
     * <p>Matched with {@code matches()}, so it must describe the whole URL. The queue's own path
     * is {@code /review/queue}, and a pattern that merely contained {@code /review/} would accept
     * the page the click started from — which would let the wait below succeed without the click
     * having navigated anywhere.
     */
    private static final String SUBMISSION_URL =
            ".+/review/submissions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final String baseUrl;

    public ReviewerQueuePage(WebDriver driver, String baseUrl) {
        this.driver = driver;
        this.baseUrl = baseUrl;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
    }

    public void open() {
        driver.get(baseUrl + "/review/queue");
    }

    public boolean isLoaded() {
        return wait.until(ExpectedConditions.titleContains("Review queue"));
    }

    /**
     * Opens the queue row whose title is exactly {@code title}, and blocks until the reviewer's
     * view of that submission is the current document.
     *
     * <p>The wait is the point of this method. {@code click()} returns once the click has been
     * dispatched, not once the browser has committed the new document, so the caller's next lookup
     * — the decision form on the detail page — can be evaluated against the queue page, where it
     * does not exist. That surfaces as a ten-second timeout naming a field on a page the browser
     * had already left. Waiting for the detail URL here means the next failure names the URL the
     * browser actually landed on.
     */
    public void openSubmission(String title) {
        // Use normalize-space() to guard against minor whitespace differences in rendered text.
        By linkLocator = By.xpath("//a[normalize-space()='" + title + "']");
        wait.until(ExpectedConditions.elementToBeClickable(linkLocator)).click();
        wait.until(ExpectedConditions.urlMatches(SUBMISSION_URL));
    }
}
