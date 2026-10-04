package com.vit.assignmentportal.e2e.pages;

import static org.assertj.core.api.Assertions.assertThat;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import java.io.File;
import java.util.regex.Pattern;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class SubmissionPage {

    /**
     * The URL a successful upload lands on: the new submission's own page.
     *
     * <p>Matched with {@code matches()}, so it must describe the whole URL — which is what
     * distinguishes this from the form's own {@code /submissions/new}. A pattern that merely
     * contained {@code /submissions/} would also match the form the request came from, and a
     * failed upload that redirects back to it would satisfy the wait immediately.
     */
    private static final Pattern SUBMISSION_URL = Pattern.compile(
            ".+/submissions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /**
     * Any refusal the upload form can render, in either of the two shapes it has.
     *
     * <p>The form has two places a refusal lands, and which one it is depends on whether the
     * refusal names an input:
     *
     * <ul>
     *   <li>A refusal about the upload as a whole — "Choose a PDF to upload.", "The uploaded file
     *       has no name." — is thrown with no field, so {@code upload.html} renders it through
     *       {@code fragments/flash.html} as {@code .alert-danger} above the form.</li>
     *   <li>A refusal about one input is thrown with that field named, and {@code upload.html}
     *       deliberately leaves the top-level alert empty so the sentence is not shown twice. It is
     *       rendered by {@code fragments/validation.html} as {@code .invalid-feedback.d-block}
     *       under the input. Every file-validation refusal is one of these:
     *       {@code SubmissionService.submit} throws the validator's message with {@code "file"}.</li>
     * </ul>
     *
     * <p>So "That file is not a valid PDF." — the refusal this suite exists to exercise — is never
     * an {@code .alert-danger}. A wait that knew only that one shape would time out on exactly the
     * case it was written for, so both are listed here.
     *
     * <p>Neither class is on the form until a refusal has been rendered, and the form is reached by
     * a fresh GET in every test that uses this object, so a match is a real transition and not a
     * message left over from an earlier attempt.
     */
    private static final By REFUSAL_MESSAGE =
            By.cssSelector(".alert-danger, .invalid-feedback.d-block");

    private static final By TITLE = By.id("title");
    private static final By COURSE_CODE = By.id("courseCode");
    private static final By FILE_INPUT = By.id("file");
    private static final By SUBMIT_BUTTON = By.cssSelector("main form button[type='submit']");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public SubmissionPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
    }

    /**
     * Fills the upload form and submits it, blocking until the POST has resolved.
     *
     * <p>Returns on either outcome. The caller decides which one it was expecting, because the
     * two tests that use this method expect opposite ones: a valid PDF must land on the new
     * submission, a disguised one must come back to this form with a refusal.
     */
    public void fillAndSubmit(String title, String courseCode, File pdfFile) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(TITLE));

        driver.findElement(TITLE).sendKeys(title);
        driver.findElement(COURSE_CODE).sendKeys(courseCode);

        attach(pdfFile);

        wait.until(ExpectedConditions.elementToBeClickable(SUBMIT_BUTTON)).click();

        // Wait for the outcome the POST produced, rather than for a proxy for it.
        //
        // The tempting proxy is "wait until #title goes stale", on the reasoning that a navigation
        // replaces the document and therefore stales every element in it. It does not work, and it
        // fails on both outcomes rather than one: ExpectedConditions.stalenessOf catches
        // NoSuchElementException and answers FALSE, so once the document has been replaced the old
        // #title is not stale but ABSENT, and the condition never becomes true. The wait then runs
        // out its full timeout against a page that had already finished loading.
        //
        // The refusal case makes it worse rather than different. A refused upload is redirected
        // back to this same form, which renders a brand-new #title — so there is a fresh element to
        // go stale in turn, and nothing here could ever detect "the POST has resolved".
        //
        // So the wait is for the resolution itself: the new submission's URL, or a refusal.
        wait.until(ignored -> resolved());
    }

    /**
     * Puts the file on the input, having first removed the two attributes that could stop the
     * form being submitted at all.
     *
     * <p>{@code required} is the one that matters. It is a real constraint, and a file input that
     * holds nothing fails it: the browser then blocks the submit and shows its own validation
     * bubble, so the click produces no request, no refusal, and no navigation — a silent
     * no-submission that {@link #fillAndSubmit} can only report as a timeout. Removing it turns
     * that case into a POST the server refuses with "Choose a PDF to upload.", which is a
     * sentence about the actual problem.
     *
     * <p>{@code accept} is removed for a different and weaker reason, and the comment says so
     * rather than pretending otherwise: it filters the file-picker dialog, and the dialog is not
     * used here — {@code sendKeys} sets the input's file list directly. Neither attribute is a
     * control (CLAUDE.md §7 rule 4); both are conveniences, and the server validates the bytes
     * regardless of what the browser was told.
     */
    private void attach(File pdfFile) {
        WebElement fileInput = driver.findElement(FILE_INPUT);

        JavascriptExecutor javascript = (JavascriptExecutor) driver;
        javascript.executeScript("arguments[0].removeAttribute('accept')", fileInput);
        javascript.executeScript("arguments[0].removeAttribute('required')", fileInput);

        fileInput.sendKeys(pdfFile.getAbsolutePath());

        // A file input that received nothing submits an empty part, and the server refuses with
        // "Choose a PDF to upload." — a refusal that reads like a validation defect and is in
        // fact an unattached fixture. Say so here, where the cause is visible, instead of ten
        // seconds later on a timeout that names the wait rather than the input.
        assertThat(fileInput.getDomProperty("value"))
                .as("the file input must hold %s before the form is submitted", pdfFile.getName())
                .isNotBlank();
    }

    /** True once the POST has produced either outcome: a new submission, or a refusal. */
    private boolean resolved() {
        if (SUBMISSION_URL.matcher(driver.getCurrentUrl()).matches()) {
            return true;
        }
        return !driver.findElements(REFUSAL_MESSAGE).isEmpty();
    }

    /**
     * Returns the sentence the form is showing after a refused upload.
     *
     * <p>Whichever of the two refusal shapes it was rendered in — see
     * {@link #REFUSAL_MESSAGE}. This is the assertion the hostile-input test rests on: the point is
     * not merely that something was refused, but that the server refused it for the right reason,
     * which for a {@code .pdf}-named file of plain text is the magic-byte check.
     */
    public String getErrorMessage() {
        return wait.until(ExpectedConditions.visibilityOfElementLocated(REFUSAL_MESSAGE)).getText();
    }
}
