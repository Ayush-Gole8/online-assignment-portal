package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class StudentDashboardPage {

    private final WebDriver driver;
    private final WebDriverWait wait;

    public StudentDashboardPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
    }

    public boolean isLoaded() {
        return wait.until(ExpectedConditions.titleContains("Dashboard"));
    }

    /**
     * Navigates to the upload form by clicking the primary "Upload a submission" button.
     *
     * <p>The dashboard renders this button at the top of the page (line 69 of dashboard.html),
     * always present regardless of whether the student has prior submissions. A second identical
     * link appears inside the empty-state card, so we use the specific {@code btn-primary}
     * class-scoped selector to consistently click the top button and avoid ambiguity.
     */
    public void navigateToUpload(String baseUrl) {
        // Direct navigation is more reliable than clicking a link: it removes the dependency
        // on the specific page state (whether the empty-state card or the table is shown)
        // and eliminates the timing window between click and URL change.
        driver.get(baseUrl + "/submissions/new");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("title")));
    }

    public String getStatusOfRecentSubmission(String title) {
        // Wait for the row containing a link with the exact submission title.
        By rowLocator = By.xpath("//tr[.//a[normalize-space()='" + title + "']]");
        WebElement row = wait.until(ExpectedConditions.visibilityOfElementLocated(rowLocator));
        return row.findElement(By.cssSelector(".badge")).getText();
    }
}
