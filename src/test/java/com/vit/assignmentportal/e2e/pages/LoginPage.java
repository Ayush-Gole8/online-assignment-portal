package com.vit.assignmentportal.e2e.pages;

import com.vit.assignmentportal.e2e.BaseE2ETest;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

public class LoginPage {

    private final WebDriver driver;
    private final WebDriverWait wait;
    private final String baseUrl;

    public LoginPage(WebDriver driver, String baseUrl) {
        this.driver = driver;
        this.baseUrl = baseUrl;
        this.wait = new WebDriverWait(driver, BaseE2ETest.WAIT);
    }

    /**
     * Navigates to the login page, fills in the credentials, submits the form, and
     * blocks until the browser has left the {@code /login} URL.
     *
     * <p>Navigation is performed inside this method rather than relying on a prior
     * {@code open()} call. This guarantees that the CSRF token embedded in the form
     * belongs to the current session, regardless of what page the browser was on before.
     *
     * <p>The wait for {@code NOT urlContains("/login")} confirms the POST completed
     * and the session cookie was updated. Without it, a subsequent {@code driver.get()}
     * call would race against the in-flight redirect.
     */
    public void loginAs(String email, String password) {
        driver.get(baseUrl + "/login");
        wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("email")));

        driver.findElement(By.id("email")).sendKeys(email);
        driver.findElement(By.id("password")).sendKeys(password);
        driver.findElement(By.cssSelector("form button[type='submit']")).click();

        wait.until(ExpectedConditions.not(ExpectedConditions.urlContains("/login")));
    }
}
