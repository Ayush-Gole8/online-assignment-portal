package com.vit.assignmentportal.e2e.pages;

import static org.assertj.core.api.Assertions.assertThat;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import java.io.File;
import java.util.List;
import java.util.function.Supplier;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

/**
 * The upload form, and the two things that can happen after it is submitted.
 *
 * <h2>What this waits for, and why it changed</h2>
 *
 * <p>This object used to decide that "the POST has resolved" by looking at two things that are
 * about the browser rather than about the application: a regular expression over the URL, and the
 * Bootstrap class {@code .alert-danger} / {@code .invalid-feedback.d-block}. Both are properties of
 * how the page happens to be built, and neither is a statement about what the server decided. When
 * the POST produced something else again — a 403 forwarded to {@code /access-denied}, a redirect
 * back to the sign-in page, an error page — the wait had no opinion about it, so it reported a
 * ten-second timeout against a page it had never described.
 *
 * <p>The waits are now for identifiers the application defines for the purpose: {@code #upload-form}
 * on the form, {@code #submission-detail} on the page a stored upload lands on, {@code #file-error}
 * under the file input for a refusal about the file, and {@code #flash-error} above the form for a
 * refusal about the upload as a whole. These are the page's own names for its own states, so a test
 * that waits on them is asking the application a question rather than guessing at its markup.
 *
 * <h2>The two outcomes are two methods</h2>
 *
 * <p>A valid upload and a refused one are different journeys with different evidence, so they are
 * {@link #submitValidPdf} and {@link #submitPdfExpectingRefusal} rather than one method whose caller
 * decides afterwards which thing it got. Each waits for the state it is named for, and neither can
 * silently pass on the other's outcome.
 */
public class SubmissionPage {

    /** The page marker: the one form whose POST creates a submission. */
    private static final By FORM = By.id("upload-form");

    private static final By TITLE = By.id("title");
    private static final By COURSE_CODE = By.id("courseCode");
    private static final By FILE_INPUT = By.id("file");

    /**
     * Scoped to the form rather than to {@code main}.
     *
     * <p>{@code main form button[type='submit']} would also work today, and it is what this class
     * used to say. It is a selector about the layout: the {@code <main>} element belongs to
     * {@code fragments/layout.html}, so it would quietly change meaning the day the layout gained
     * anything else with a submit button in it. {@code #upload-form} is this page's own name for
     * this page's own form.
     */
    private static final By SUBMIT_BUTTON = By.cssSelector("#upload-form button[type='submit']");

    /** The page a stored upload is answered with. */
    private static final By SUBMISSION_DETAIL = By.id("submission-detail");

    /**
     * A refusal about one input, under that input.
     *
     * <p>The id is built by {@code fragments/validation} as {@code field + '-error'}, so this is the
     * same string the input's {@code aria-describedby} points at. Every file refusal is one of
     * these: {@code SubmissionService} throws the validator's message with the field {@code "file"},
     * which is why {@code #file-error} — and not the alert above the form — is where
     * "That file is not a valid PDF." appears.
     */
    private static final By FIELD_REFUSAL = By.id("file-error");

    /**
     * A refusal about the upload as a whole, above the form.
     *
     * <p>Rendered by {@code fragments/flash} when no single field is to blame — a missing part, an
     * oversized request caught by the container. The upload form leaves this empty when a field is
     * named, so the two never both carry a sentence.
     */
    private static final By WHOLE_REFUSAL = By.id("flash-error");

    private final WebDriver driver;
    private final WebDriverWait wait;

    public SubmissionPage(WebDriver driver) {
        this.driver = driver;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
    }

    /**
     * Uploads a file the server is expected to accept, and returns the page it lands on.
     *
     * <p>Waits for {@code #submission-detail} — the student's view of the submission that was just
     * created. That is the end of the journey; a caller that gets this object back has a page with a
     * status on it and nothing left to wait for.
     *
     * @throws AssertionError if the upload was refused instead, naming the sentence the form showed
     */
    public SubmissionDetailsPage submitValidPdf(String title, String courseCode, File pdfFile) {
        fillAndSubmit(title, courseCode, pdfFile);
        awaitResolution();

        if (isPresent(SUBMISSION_DETAIL)) {
            return new SubmissionDetailsPage(driver);
        }
        throw new AssertionError(
                "the upload was expected to be stored and answered with the new submission's page, "
                        + "but the form refused it: " + refusalText() + System.lineSeparator()
                        + describePage());
    }

    /**
     * Uploads a file the server is expected to refuse, and returns the sentence it was refused with.
     *
     * <p>Waits for the refusal to be <em>rendered</em>, then reads it. The wait and the assertion are
     * both about the same element, so what is asserted is what was waited for — a file that is
     * refused for a different reason fails on the message rather than passing on the mere presence of
     * an error.
     *
     * @throws AssertionError if the upload was accepted instead
     */
    public String submitPdfExpectingRefusal(String title, String courseCode, File pdfFile) {
        fillAndSubmit(title, courseCode, pdfFile);
        awaitResolution();

        if (isPresent(FIELD_REFUSAL) || isPresent(WHOLE_REFUSAL)) {
            return refusalText();
        }
        throw new AssertionError(
                "the upload was expected to be refused, but it was accepted: the browser reached "
                        + "the submission's own page" + System.lineSeparator() + describePage());
    }

    /** Fills the form, attaching the file first, and clicks submit. */
    private void fillAndSubmit(String title, String courseCode, File pdfFile) {
        wait.until(ExpectedConditions.visibilityOfElementLocated(TITLE));

        driver.findElement(TITLE).sendKeys(title);
        driver.findElement(COURSE_CODE).sendKeys(courseCode);

        attach(pdfFile);

        wait.until(ExpectedConditions.elementToBeClickable(SUBMIT_BUTTON)).click();
    }

    /**
     * Blocks until the POST has produced one of the two outcomes this form can produce.
     *
     * <p>The tempting alternative is to wait for an element to go stale, on the reasoning that a
     * navigation replaces the document. It does not work, and it fails on both outcomes rather than
     * one: {@code ExpectedConditions.stalenessOf} catches {@code NoSuchElementException} and answers
     * {@code false}, so once the document has been replaced the old element is not stale but
     * <em>absent</em>, and the condition never becomes true. A refusal makes it worse — it redirects
     * back to this same form, which renders a brand-new element with the same id, so there was never
     * a moment when the thing being watched had gone away.
     *
     * <p>So the wait is for the outcome. When none arrives, the failure describes the page that was
     * there instead, because that description is the whole diagnosis and it cannot be recovered
     * afterwards — the browser is closed at the end of the test.
     */
    private void awaitResolution() {
        try {
            wait.until(ignored -> isPresent(SUBMISSION_DETAIL)
                    || isPresent(FIELD_REFUSAL)
                    || isPresent(WHOLE_REFUSAL));
        } catch (TimeoutException expired) {
            throw new AssertionError(
                    "the upload POST produced neither a stored submission nor a refusal within "
                            + BaseE2ETest.WAIT.toSeconds() + " seconds." + System.lineSeparator()
                            + describePage(), expired);
        }
    }

    /**
     * Puts the file on the input, having first removed the two attributes that could stop the form
     * being submitted at all.
     *
     * <p>{@code required} is the one that matters. It is a real constraint, and a file input that
     * holds nothing fails it: the browser then blocks the submit and shows its own validation
     * bubble, so the click produces no request, no refusal, and no navigation — a silent
     * no-submission that the wait above can only report as a page that never moved. Removing it
     * turns that case into a POST the server refuses with "Choose a PDF to upload.", which is a
     * sentence about the actual problem.
     *
     * <p>{@code accept} is removed for a different and weaker reason, and the comment says so rather
     * than pretending otherwise: it filters the file-picker dialog, and the dialog is not used here —
     * {@code sendKeys} sets the input's file list directly. Neither attribute is a control
     * ({@code CLAUDE.md} §7 rule 4); both are conveniences, and the server validates the bytes
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
        // seconds later against a page whose real problem was the attachment.
        assertThat(fileInput.getDomProperty("value"))
                .as("the file input must hold %s before the form is submitted", pdfFile.getName())
                .isNotBlank();
    }

    /** The sentence the form refused the upload with, from whichever of the two shapes it used. */
    private String refusalText() {
        By located = isPresent(FIELD_REFUSAL) ? FIELD_REFUSAL : WHOLE_REFUSAL;
        return isPresent(located) ? driver.findElement(located).getText() : "(no refusal is visible)";
    }

    /**
     * Facts about the page as it stands, for a failure message.
     *
     * <p>Every read is guarded: this runs while a test is already failing, and a diagnostic that
     * throws is a diagnostic that has replaced the failure it was written to explain.
     */
    private String describePage() {
        String newline = System.lineSeparator();
        return "the browser was on:"
                + newline + "  url:            " + read(driver::getCurrentUrl)
                + newline + "  title:          " + read(driver::getTitle)
                + newline + "  upload form:    " + (isPresent(FORM) ? "present" : "gone")
                + newline + "  title field:    " + fieldValue(TITLE)
                + newline + "  file field:     " + fieldValue(FILE_INPUT)
                + newline + "  visible alerts: " + alertTexts()
                + newline + "  page says:      " + firstLineOfBodyText();
    }

    private String fieldValue(By field) {
        if (!isPresent(field)) {
            return "(absent)";
        }
        return read(() -> driver.findElement(field).getDomProperty("value"));
    }

    /** Every rendered alert on the page, in document order — the refusals and confirmations. */
    private String alertTexts() {
        List<WebElement> alerts = driver.findElements(By.cssSelector(".alert"));
        if (alerts.isEmpty()) {
            return "(none)";
        }
        StringBuilder sentences = new StringBuilder();
        for (WebElement alert : alerts) {
            if (sentences.length() > 0) {
                sentences.append(" | ");
            }
            String sentence = read(alert::getText);
            sentences.append(sentence);
        }
        return sentences.toString();
    }

    /** The first line of the rendered text, which is usually enough to name the page. */
    private String firstLineOfBodyText() {
        String text = read(() -> driver.findElement(By.tagName("body")).getText()).strip();
        int endOfFirstLine = text.indexOf('\n');
        return endOfFirstLine < 0 ? text : text.substring(0, endOfFirstLine);
    }

    /**
     * Whether an element is in the document.
     *
     * <p>Presence rather than visibility. Every marker here is rendered by a {@code th:if} and so
     * exists only when the state it names is the state the application is in; an element that has
     * been rendered is the signal. Asking for visibility as well would add a second condition that
     * means something different — "rendered, but scrolled out of the viewport" would fail — and
     * these markers are not styled to be hidden.
     */
    private boolean isPresent(By locator) {
        return !driver.findElements(locator).isEmpty();
    }

    private String read(Supplier<String> fromBrowser) {
        try {
            return fromBrowser.get();
        } catch (Exception unreadable) {
            return "(unreadable: " + unreadable.getClass().getSimpleName() + ")";
        }
    }
}
