package com.vit.assignmentportal.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.vit.assignmentportal.e2e.pages.LoginPage;
import com.vit.assignmentportal.e2e.pages.ReviewerDetailsPage;
import com.vit.assignmentportal.e2e.pages.ReviewerQueuePage;
import com.vit.assignmentportal.e2e.pages.StudentDashboardPage;
import com.vit.assignmentportal.e2e.pages.SubmissionPage;
import com.vit.assignmentportal.support.TestCategories;
import java.io.File;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag(TestCategories.E2E)
class ReviewerWorkflowE2E extends BaseE2ETest {

    @Test
    void reviewerCanApproveSubmission() throws Exception {
        String title = "Submission to Approve " + System.currentTimeMillis();
        createSubmissionAsStudent(title);

        new LoginPage(driver, getBaseUrl()).loginAs(REVIEWER_EMAIL, PASSWORD);

        ReviewerQueuePage queuePage = new ReviewerQueuePage(driver, getBaseUrl());
        queuePage.open();
        assertThat(queuePage.isLoaded()).isTrue();

        queuePage.openSubmission(title);
        new ReviewerDetailsPage(driver).approve();

        verifyStudentSeesStatus(title, "APPROVED");
    }

    @Test
    void reviewerCanRejectSubmissionWithReason() throws Exception {
        String title = "Submission to Reject " + System.currentTimeMillis();
        createSubmissionAsStudent(title);

        new LoginPage(driver, getBaseUrl()).loginAs(REVIEWER_EMAIL, PASSWORD);

        ReviewerQueuePage queuePage = new ReviewerQueuePage(driver, getBaseUrl());
        queuePage.open();
        assertThat(queuePage.isLoaded()).isTrue();

        queuePage.openSubmission(title);
        new ReviewerDetailsPage(driver).reject("The formatting is incorrect.");

        verifyStudentSeesStatus(title, "REJECTED");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Logs in as the student, submits a valid PDF with the given title, then logs out so the test
     * can continue as the reviewer.
     *
     * <p>Uses {@link SubmissionPage#submitValidPdf}, the same method the student's own journey test
     * uses. The reviewer's tests need a submission to decide, and the only honest way to get one is
     * to make one the way a student does — through the browser, through the form. A second, private
     * submission path here would be a second definition of "a submission exists", and it would drift
     * from the first.
     */
    private void createSubmissionAsStudent(String title) throws Exception {
        new LoginPage(driver, getBaseUrl()).loginAs(STUDENT_EMAIL, PASSWORD);

        new StudentDashboardPage(driver).navigateToUpload(getBaseUrl());

        File pdf = createDummyPdf("reviewer-test");
        new SubmissionPage(driver).submitValidPdf(title, "REV101", pdf);

        logout();
    }

    /**
     * Logs out the reviewer, logs back in as the student, navigates to the dashboard,
     * and asserts that the named submission shows the expected status badge.
     */
    private void verifyStudentSeesStatus(String title, String expectedStatus) {
        logout();

        new LoginPage(driver, getBaseUrl()).loginAs(STUDENT_EMAIL, PASSWORD);

        driver.get(getBaseUrl() + "/student/dashboard");
        StudentDashboardPage dashboardPage = new StudentDashboardPage(driver);
        assertThat(dashboardPage.isLoaded()).isTrue();
        assertThat(dashboardPage.getStatusOfRecentSubmission(title)).isEqualTo(expectedStatus);
    }
}
