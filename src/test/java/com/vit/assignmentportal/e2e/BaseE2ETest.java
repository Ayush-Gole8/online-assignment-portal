package com.vit.assignmentportal.e2e;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(ScreenshotExtension.class)
public abstract class BaseE2ETest extends MySqlIntegrationTest {

    @LocalServerPort
    protected int port;

    @Autowired
    protected UserService userService;

    protected static final String PASSWORD = "password123";
    protected static final String STUDENT_EMAIL = "student@example.com";
    protected static final String REVIEWER_EMAIL = "reviewer@example.com";

    protected WebDriver driver;

    /**
     * Default explicit wait timeout, used throughout all page objects.
     *
     * <p>Implicit waits are deliberately NOT set on the driver. Mixing implicit and explicit
     * waits produces undefined behaviour: when WebDriverWait polls for a condition, the implicit
     * wait causes each findElement call to block for its own timeout before throwing the
     * NoSuchElementException that the expected condition relies on catching, which causes the
     * outer explicit wait to observe wildly inflated polling intervals and time out early.
     */
    public static final Duration WAIT = Duration.ofSeconds(10);

    @BeforeEach
    void setUp() {
        if (!userService.existsByEmail(STUDENT_EMAIL)) {
            userService.create(STUDENT_EMAIL, PASSWORD, Role.STUDENT);
        }
        if (!userService.existsByEmail(REVIEWER_EMAIL)) {
            userService.create(REVIEWER_EMAIL, PASSWORD, Role.REVIEWER);
        }

        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new");
        options.addArguments("--disable-gpu");
        options.addArguments("--window-size=1920,1080");
        options.addArguments("--no-sandbox");
        options.addArguments("--disable-dev-shm-usage");
        driver = new ChromeDriver(options);
        // Do NOT set implicitlyWait here — see class-level Javadoc.
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
    }

    @AfterEach
    void tearDown() {
        if (driver != null) {
            driver.quit();
        }
    }

    public WebDriver getDriver() {
        return driver;
    }

    protected String getBaseUrl() {
        return "http://localhost:" + port;
    }

    /**
     * Logs the current browser session out by clearing the session cookie and navigating to
     * the login page. This is more reliable than clicking the Sign out button, which requires
     * a POST through the CSRF filter and is susceptible to Thymeleaf URL rendering differences.
     *
     * <p>Clearing the JSESSIONID cookie is safe here: the Testcontainer's MySQL database retains
     * all rows, so a subsequent login as either role will find the same data. The server creates
     * a fresh session (with a fresh CSRF token) on the next page load, so the subsequent login
     * form is fully valid.
     */
    protected void logout() {
        driver.manage().deleteAllCookies();
        driver.get(getBaseUrl() + "/login");
        new WebDriverWait(driver, WAIT)
                .until(ExpectedConditions.visibilityOfElementLocated(By.id("email")));
    }

    protected File createDummyPdf(String prefix) throws Exception {
        Path tempFile = Files.createTempFile(prefix, ".pdf");
        // Minimal but structurally valid PDF header that passes the magic-byte check.
        String content = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n";
        Files.writeString(tempFile, content);
        return tempFile.toFile();
    }

    /**
     * Creates a file whose name ends in {@code .pdf} but whose content is plain text,
     * so Chrome's {@code accept="application/pdf,.pdf"} filter passes it but the server's
     * magic-byte check refuses it with "That file is not a valid PDF."
     */
    protected File createDummyTextFile(String prefix) throws Exception {
        Path tempFile = Files.createTempFile(prefix, ".pdf");
        Files.writeString(tempFile, "This is not a PDF");
        return tempFile.toFile();
    }
}
