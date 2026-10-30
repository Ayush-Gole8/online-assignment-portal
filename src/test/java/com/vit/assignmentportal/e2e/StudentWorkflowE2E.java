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

        // Returns the stored submission's own page, having waited for it. There is nothing left to
        // poll for here: if the upload had been refused, this would have failed with the sentence
        // the form showed rather than handing back a page that never rendered.
        SubmissionDetailsPage detailsPage =
                new SubmissionPage(driver).submitValidPdf("My First E2E Submission", "E2E101", pdf);

        assertThat(detailsPage.isLoaded()).isTrue();
        assertThat(detailsPage.getStatus()).isEqualTo("SUBMITTED");
    }

    @Test
    void studentCannotSubmitInvalidFile() throws Exception {
        new LoginPage(driver, getBaseUrl()).loginAs(STUDENT_EMAIL, PASSWORD);

        driver.get(getBaseUrl() + "/student/dashboard");
        assertThat(new StudentDashboardPage(driver).isLoaded()).isTrue();

        new StudentDashboardPage(driver).navigateToUpload(getBaseUrl());

        // The file has a .pdf extension (so Chrome's accept= attribute passes it) and plain-text
        // content, which fails the server's magic-byte check. The lesson of this test is that the
        // client-side filter is a convenience and not a control: the browser let it through and the
        // server refused it (CLAUDE.md §7 rule 4).
        File disguisedFile = createDummyTextFile("invalid-submission");

        // Waits for the refusal to be rendered under the file input and returns it. Asserting on
        // the sentence, not merely on the presence of an error: "refused" and "refused for the
        // right reason" are different claims.
        String refusal = new SubmissionPage(driver)
                .submitPdfExpectingRefusal("Invalid File Submission", "E2E101", disguisedFile);

        assertThat(refusal).isEqualTo("That file is not a valid PDF.");
    }
}
