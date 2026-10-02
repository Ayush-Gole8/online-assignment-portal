package com.vit.assignmentportal.submission;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The student's dashboard: who may reach it, and what it is allowed to show them.
 *
 * <p>Two properties are asserted from the outside rather than inferred from the service. The first
 * is the <strong>route rule</strong>: {@code /student/**} is {@code STUDENT} only, so a reviewer is
 * refused with a 403 and an anonymous visitor is sent to the login page. The second is
 * <strong>isolation</strong>: the counts and the recent list are the signed-in student's own, and
 * the absence of another student's title is asserted in the same body that shows their own — because
 * the dashboard is the page where a leak would be quiet, a number rather than a row.
 *
 * <p>The counts are read from the same tables the list reads, but by counted queries rather than by
 * loading rows. That is a performance choice, not a security one, and it is deliberately not what is
 * asserted here: what is asserted is that the page a student sees describes that student's work.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StudentDashboardIT extends MySqlIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String PDF = "application/pdf";

    private static final byte[] PDF_BYTES = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    // ------------------------------------------------------------------------
    // Who may reach it
    // ------------------------------------------------------------------------

    @Test
    void redirectsAnonymousVisitorsToTheLoginPage() throws Exception {
        mockMvc.perform(get("/student/dashboard"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /**
     * A reviewer is refused, and refused with a 403 rather than a redirect.
     *
     * <p>The dashboard is the student's own work summarised. A reviewer has no submissions of their
     * own to summarise, and admitting them would show them a page whose every link is scoped to "my
     * work" that is not theirs. The refusal is the same shape as a student reaching the review
     * queue, asserted in {@code AuthorizationIT} from the other side.
     */
    @Test
    void refusesAReviewerTheDashboard() throws Exception {
        MockHttpSession reviewer = signIn("dashboard-reviewer", Role.REVIEWER);

        mockMvc.perform(get("/student/dashboard").session(reviewer))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------
    // What it shows
    // ------------------------------------------------------------------------

    @Test
    void servesTheDashboardToAStudent() throws Exception {
        MockHttpSession student = signIn("dashboard-student", Role.STUDENT);

        mockMvc.perform(get("/student/dashboard").session(student))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/dashboard"))
                .andExpect(content().string(containsString("Awaiting review")));
    }

    /**
     * A student who has handed nothing in is told so, with the action that changes it.
     *
     * <p>An empty dashboard is the ordinary state of a new account, not a failure, and it is the
     * state most likely to be rendered as three zeroes over an empty table. The sentence and the
     * upload link are what make it read as "nothing yet" rather than "something broke".
     */
    @Test
    void showsAnEmptyStateToAStudentWithNoSubmissions() throws Exception {
        MockHttpSession student = signIn("dashboard-empty", Role.STUDENT);

        mockMvc.perform(get("/student/dashboard").session(student))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("You have not submitted anything yet")))
                .andExpect(content().string(containsString("/submissions/new")));
    }

    /** A student's own recent work appears on their dashboard. */
    @Test
    void showsTheStudentsOwnRecentSubmission() throws Exception {
        MockHttpSession student = signedInWithSubmission("dashboard-own", "My dashboard essay");

        mockMvc.perform(get("/student/dashboard").session(student))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("My dashboard essay")));
    }

    /**
     * The dashboard shows one student's work and nobody else's.
     *
     * <p>Both halves are asserted in the same response: a page that showed only its own work because
     * it showed nothing at all would pass the absence check and fail the presence one. The two
     * students exist simultaneously, so the second is genuinely present in the database while the
     * first is being rendered.
     */
    @Test
    void neverShowsAnotherStudentsWork() throws Exception {
        MockHttpSession first = signedInWithSubmission("dashboard-isolation-owner", "Belongs to the first");
        signedInWithSubmission("dashboard-isolation-other", "Belongs to the second");

        mockMvc.perform(get("/student/dashboard").session(first))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Belongs to the first")))
                .andExpect(content().string(not(containsString("Belongs to the second"))));
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Signs in as a fresh student and hands in one submission.
     *
     * <p>The upload goes through the real route rather than the service, so the dashboard is read in
     * a state the application actually produced — a row written by {@code SubmissionService} with
     * its history row beside it, not a fixture assembled by the test.
     */
    private MockHttpSession signedInWithSubmission(String slug, String title) throws Exception {
        MockHttpSession session = signIn(slug, Role.STUDENT);

        mockMvc.perform(multipart("/submissions")
                        .file(new MockMultipartFile("file", slug + ".pdf", PDF, PDF_BYTES))
                        .param("title", title)
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(session))
                .andExpect(status().is3xxRedirection());

        return session;
    }

    /** Signs in as a fresh account of the given role and returns the session that login produced. */
    private MockHttpSession signIn(String slug, Role role) throws Exception {
        String email = slug + "@example.com";
        if (!userService.existsByEmail(email)) {
            userService.create(email, PASSWORD, role);
        }

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .param("email", email)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        return session;
    }
}
