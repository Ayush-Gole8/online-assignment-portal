package com.vit.assignmentportal.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.vit.assignmentportal.e2e.pages.LoginPage;
import com.vit.assignmentportal.e2e.pages.StudentDashboardPage;
import com.vit.assignmentportal.e2e.pages.SubmissionDetailsPage;
import com.vit.assignmentportal.e2e.pages.SubmissionPage;
import com.vit.assignmentportal.support.TestCategories;
import java.io.File;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(TestCategories.E2E)
class StudentWorkflowE2E extends BaseE2ETest {

    @Test
    void studentCanLoginAndSubmitValidPdf() throws Exception {
        new LoginPage(driver, getBaseUrl()).loginAs(STUDENT_EMAIL, PASSWORD);

        driver.get(getBaseUrl() + "/student/dashboard");
        assertThat(new StudentDashboardPage(driver).isLoaded()).isTrue();

        new StudentDashboardPage(driver).navigateToUpload(getBaseUrl());

        File pdf = createDummyPdf("valid-submission");
        new SubmissionPage(driver).fillAndSubmit("My First E2E Submission", "E2E101", pdf);

        SubmissionDetailsPage detailsPage = new SubmissionDetailsPage(driver);
        assertThat(detailsPage.isLoaded()).isTrue();
        assertThat(detailsPage.getStatus()).isEqualTo("SUBMITTED");
    }

    @Test
    void studentCannotSubmitInvalidFile() throws Exception {
        new LoginPage(driver, getBaseUrl()).loginAs(STUDENT_EMAIL, PASSWORD);

        new StudentDashboardPage(driver).navigateToUpload(getBaseUrl());

        // File has .pdf extension (passes Chrome's accept= attribute) but plain-text
        // content that fails the server's magic-byte check.
        File disguisedFile = createDummyTextFile("invalid-submission");
        new SubmissionPage(driver).fillAndSubmit("Invalid File Submission", "E2E101", disguisedFile);

        assertThat(new SubmissionPage(driver).getErrorMessage())
                .isEqualTo("That file is not a valid PDF.");
    }
}
