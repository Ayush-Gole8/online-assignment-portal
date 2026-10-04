package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class SubmissionDetailsPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    public SubmissionDetailsPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
    }

    /**
     * Confirms we are on a submission detail page (not the upload form).
     *
     * <p>Uses a UUID URL pattern ({@code /submissions/<uuid>}) rather than the loose
     * {@code urlContains("/submissions/")} check, which also matches {@code /submissions/new}.
     * Without the UUID check, a failed upload that redirects back to the upload form would
     * satisfy the URL condition immediately and the subsequent {@code .badge} wait would
     * time out with a misleading "element not found" error.
     */
    public boolean isLoaded() {
        // Standard UUID regex: 8-4-4-4-12 hex groups.
        wait.until(ExpectedConditions.urlMatches(
                ".+/submissions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        return wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".badge"))) != null;
    }

    public String getStatus() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(
                By.cssSelector(".badge"))).getText();
    }
}
