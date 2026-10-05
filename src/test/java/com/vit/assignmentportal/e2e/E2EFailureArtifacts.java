package com.vit.assignmentportal.e2e;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.opentest4j.TestAbortedException;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

/**
 * Captures what the browser was looking at when an end-to-end test fails.
 *
 * <p>A Selenium failure names the wait that expired and the element it wanted, and says nothing
 * about the page that was actually on screen. That is the whole diagnosis for a browser test — the
 * server's answer is the only evidence of what it decided — so a failed run that leaves no page
 * behind costs a second run to learn anything.
 *
 * <h2>Why this is an {@link InvocationInterceptor} and not a {@code TestWatcher}</h2>
 *
 * <p>It used to be a {@code TestWatcher}, and it never produced a single file. JUnit invokes
 * {@code TestWatcher.testFailed} <em>after</em> the class's {@code @AfterEach} methods, and
 * {@code BaseE2ETest.tearDown} quits the driver. The extension therefore ran against a dead
 * session and every capture ended in
 * {@code Failed to save screenshot: Session ID is null. Using WebDriver after calling quit()?} —
 * visible in the Failsafe report's {@code system-err}, which is how a diagnostic that silently
 * does nothing was found.
 *
 * <p>{@code interceptTestMethod} wraps the test method itself, so the failure passes through this
 * class <em>before</em> any {@code @AfterEach} runs and before JUnit tears anything down. The
 * ordering is not a convention to remember; it is the position of the call.
 *
 * <p>Capturing must never replace the failure it exists to explain, so every step below is
 * individually guarded and the original throwable is always rethrown unchanged.
 */
public class E2EFailureArtifacts implements InvocationInterceptor {

    /** Where a failed test's evidence is written. Only ever written on failure. */
    private static final Path ARTIFACTS = Paths.get("target", "e2e-artifacts");

    /** How much of the stack trace {@code metadata.txt} carries: enough to locate the failure. */
    private static final int STACK_FRAMES = 12;

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        try {
            invocation.proceed();
        } catch (TestAbortedException aborted) {
            // An assumption failed, not the test. There is no failure to diagnose, and writing
            // artifacts for it would report a problem where the suite deliberately skipped one.
            throw aborted;
        } catch (Throwable failure) {
            // Wrapped, not merely careful. Every step inside capture() is already guarded, and this
            // guard exists anyway: the one thing this class must never do is throw, because a
            // throwable raised here would propagate in place of the failure a reader came for, and
            // the run would report a broken diagnostic instead of the defect it was written to
            // describe.
            try {
                capture(extensionContext, failure);
            } catch (Throwable diagnosticFailure) {
                System.err.println("Could not write E2E failure artifacts for "
                        + extensionContext.getDisplayName() + ": " + diagnosticFailure);
            }
            throw failure;
        }
    }

    private void capture(ExtensionContext context, Throwable failure) {
        if (!(context.getRequiredTestInstance() instanceof BaseE2ETest test)) {
            return;
        }
        WebDriver driver = test.getDriver();
        if (driver == null) {
            return;
        }

        Path directory = ARTIFACTS.resolve(context.getRequiredTestMethod().getName());
        try {
            Files.createDirectories(directory);
        } catch (Exception cause) {
            System.err.println("Could not create " + directory.toAbsolutePath()
                    + " for a failed E2E test: " + cause);
            return;
        }

        writeScreenshot(driver, directory);
        writePageSource(driver, directory);
        writeMetadata(context, failure, driver, directory);

        System.out.println("E2E failure artifacts written to " + directory.toAbsolutePath());
    }

    /**
     * The visible viewport, as a PNG.
     *
     * <p>Described as the viewport rather than a "full page" because that is what
     * {@link TakesScreenshot} returns: rendering the whole scrollable document needs either a CDP
     * call or a browser that supports it, and this suite is written against the W3C protocol so that
     * it does not depend on a CDP binding matching the installed Chrome. The window is opened at
     * 1920x1080 for every test, so the capture covers the region a person would see.
     */
    private void writeScreenshot(WebDriver driver, Path directory) {
        try {
            File captured = ((TakesScreenshot) driver).getScreenshotAs(OutputType.FILE);
            Files.copy(captured.toPath(), directory.resolve("screenshot.png"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception cause) {
            warn("screenshot.png", cause);
        }
    }

    /** The document as the server sent it, which is usually where the answer is. */
    private void writePageSource(WebDriver driver, Path directory) {
        try {
            Files.writeString(directory.resolve("page-source.html"),
                    driver.getPageSource(), StandardCharsets.UTF_8);
        } catch (Exception cause) {
            warn("page-source.html", cause);
        }
    }

    /**
     * The facts a reader would otherwise have to reconstruct: which test, when, on what URL and
     * title, and what went wrong.
     *
     * <p>The URL and the title are read here rather than passed in, because the interesting one is
     * the page the browser had reached when the assertion failed — which is exactly what the
     * exception does not carry.
     */
    private void writeMetadata(ExtensionContext context, Throwable failure, WebDriver driver,
                               Path directory) {
        StringBuilder metadata = new StringBuilder();
        metadata.append("test:        ")
                .append(context.getRequiredTestClass().getName())
                .append('#')
                .append(context.getRequiredTestMethod().getName())
                .append(System.lineSeparator());
        metadata.append("captured:    ").append(Instant.now()).append(System.lineSeparator());
        metadata.append("url:         ").append(read(driver::getCurrentUrl)).append(System.lineSeparator());
        metadata.append("title:       ").append(read(driver::getTitle)).append(System.lineSeparator());
        metadata.append("failure:     ").append(failure.getClass().getName()).append(System.lineSeparator());
        metadata.append("message:     ").append(failure.getMessage()).append(System.lineSeparator());
        metadata.append("frames:").append(System.lineSeparator());
        appendStack(metadata, failure);

        try {
            Files.writeString(directory.resolve("metadata.txt"), metadata.toString(),
                    StandardCharsets.UTF_8);
        } catch (Exception cause) {
            warn("metadata.txt", cause);
        }
    }

    private void appendStack(StringBuilder metadata, Throwable failure) {
        StackTraceElement[] frames = failure.getStackTrace();
        for (int index = 0; index < Math.min(frames.length, STACK_FRAMES); index++) {
            metadata.append("  at ").append(frames[index]).append(System.lineSeparator());
        }
        if (frames.length > STACK_FRAMES) {
            metadata.append("  ... ").append(frames.length - STACK_FRAMES)
                    .append(" more").append(System.lineSeparator());
        }
    }

    /**
     * A value from the browser that may not be readable.
     *
     * <p>Every read here is a round trip that can fail against a browser that has just crashed,
     * which is one of the situations artifacts are wanted for. A missing line is a far better
     * outcome than an exception thrown out of the diagnostic.
     */
    private String read(Supplier<String> fromBrowser) {
        try {
            return fromBrowser.get();
        } catch (Exception unreadable) {
            return "(unreadable: " + unreadable.getClass().getSimpleName() + ")";
        }
    }

    private void warn(String artifact, Exception cause) {
        System.err.println("Could not write " + artifact + " for a failed E2E test: " + cause);
    }
}
