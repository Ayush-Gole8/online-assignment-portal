package com.vit.assignmentportal.e2e;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;

public class ScreenshotExtension implements TestWatcher {

    @Override
    public void testFailed(ExtensionContext context, Throwable cause) {
        Object testInstance = context.getRequiredTestInstance();
        if (testInstance instanceof BaseE2ETest baseTest) {
            WebDriver driver = baseTest.getDriver();
            if (driver instanceof TakesScreenshot takesScreenshot) {
                try {
                    File screenshot = takesScreenshot.getScreenshotAs(OutputType.FILE);
                    Path targetDir = Paths.get("target/e2e-artifacts");
                    Files.createDirectories(targetDir);
                    
                    String testName = context.getRequiredTestMethod().getName();
                    Path targetFile = targetDir.resolve(testName + "-" + System.currentTimeMillis() + ".png");
                    Files.copy(screenshot.toPath(), targetFile);
                    System.out.println("Saved screenshot to " + targetFile.toAbsolutePath());
                } catch (Exception e) {
                    // Deliberately Exception and not IOException. This extension runs to make a
                    // failure easier to diagnose, and it must never become the failure. A driver
                    // that has already been quit, a session that Chrome has dropped, a disk that
                    // refused the write — each throws something other than IOException, and
                    // letting any of them escape would replace the real test failure (which is
                    // what the reader needs) with a failure of the screenshot tooling.
                    System.err.println("Failed to save screenshot: " + e.getMessage());
                }
            }
        }
    }
}
