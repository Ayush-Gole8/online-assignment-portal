package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The reviewer's queue: submissions waiting for a decision.
 */
public class ReviewerQueuePage {

    private static final By PAGE = By.id("review-queue");

    /** The reviewer's view of one submission — the page the queue's rows lead to. */
    private static final By REVIEW_DETAIL = By.id("review-detail");

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

    /**
     * Whether the queue has rendered.
     *
     * <p>Waits for {@code #review-queue} rather than for the words "Review queue" in the document
     * title. The detail page's own title contains the same words, so a title match is a question
     * that both pages answer yes to.
     */
    public boolean isLoaded() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(PAGE)) != null;
    }

    /**
     * Opens the queue row whose title is exactly {@code title}, and blocks until the reviewer's
     * view of that submission is the current document.
     *
     * <p>The wait is the point of this method. {@code click()} returns once the click has been
     * <em>dispatched</em>, not once the browser has committed the new document, so the caller's next
     * lookup — the decision buttons on the detail page — can be evaluated against the queue page,
     * where they do not exist. That surfaces as a timeout naming a button on a page the browser had
     * already left. Waiting for {@code #review-detail} here means the next failure names the page
     * that was actually there.
     */
    public void openSubmission(String title) {
        // normalize-space() guards against whitespace the browser adds around rendered text.
        By link = By.xpath("//a[normalize-space()='" + title + "']");
        org.openqa.selenium.WebElement linkEl = wait.until(ExpectedConditions.presenceOfElementLocated(link));
        ((org.openqa.selenium.JavascriptExecutor) driver).executeScript("arguments[0].click();", linkEl);
        wait.until(ExpectedConditions.visibilityOfElementLocated(REVIEW_DETAIL));
    }
}
