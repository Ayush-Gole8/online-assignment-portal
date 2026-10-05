package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The student's dashboard: the page a student lands on after signing in.
 */
public class StudentDashboardPage {

    private static final By PAGE = By.id("student-dashboard");

    /** The upload form's own marker, waited for so that "the form is up" is not a guess. */
    private static final By UPLOAD_FORM = By.id("upload-form");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public StudentDashboardPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
    }

    /**
     * Whether the dashboard has rendered.
     *
     * <p>Asks the document, rather than looking for the word "Dashboard" in its title. A title is a
     * string that happens to appear on this page and on any other page that chose the same word;
     * {@code id="student-dashboard"} is this page saying which page it is.
     */
    public boolean isLoaded() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(PAGE)) != null;
    }

    /**
     * Opens the upload form directly.
     *
     * <p>Direct navigation rather than a click on the dashboard's button: the button is rendered
     * twice — once in the action row and once inside the empty-state card — and which of the two is
     * present depends on whether the student has submitted anything yet. Navigating removes that
     * dependency, and the wait is for the form itself, so the caller returns with the page it
     * intends to fill in rather than merely having asked for it.
     */
    public void navigateToUpload(String baseUrl) {
        driver.get(baseUrl + "/submissions/new");
        wait.until(ExpectedConditions.visibilityOfElementLocated(UPLOAD_FORM));
    }

    /**
     * The status of the most recent submission whose title is exactly {@code title}.
     *
     * <p>Read from the badge's {@code data-status} — the state — rather than from its text or its
     * colour class. The row is found by the link it contains, which is the title as the student sees
     * it; the status is then read from within that row, so two submissions with different states
     * cannot be confused for one another.
     */
    public String getStatusOfRecentSubmission(String title) {
        // normalize-space() guards against whitespace the browser adds around rendered text.
        By row = By.xpath("//tr[.//a[normalize-space()='" + title + "']]");
        WebElement found = wait.until(ExpectedConditions.visibilityOfElementLocated(row));
        return found.findElement(By.cssSelector("[data-status]")).getAttribute("data-status");
    }
}
