package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Who may see what, and — more importantly — who may not.
 *
 * <p>Student isolation is the security boundary this milestone introduces, so it is tested from
 * the outside: two real accounts, two real sessions, two submissions, and a request from one
 * account for the other's work. Testing it by calling the service would prove that
 * {@code findByIdAndStudentId} works; it would not prove that the controller calls it, or that the
 * route is guarded, or that a missing row renders as a 404 rather than a stack trace.
 *
 * <p>The expected answer to "somebody else's submission" is <strong>404, not 403</strong>. A 403
 * would confirm the identifier exists and belongs to someone — which is exactly the fact an
 * attacker enumerating identifiers is trying to learn. The not-found response is indistinguishable
 * from asking for a submission that never existed, and both cases are asserted here so that a
 * future change cannot make one of them informative.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SubmissionAccessIT extends MySqlIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";
    private static final String PDF = "application/pdf";

    private static final byte[] PDF_BYTES = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------------
    // Anonymous access
    // ------------------------------------------------------------------------

    /**
     * Every submission route refuses an anonymous visitor with a redirect to the login page.
     *
     * <p>Parameterised over the paths rather than written out three times, so a fourth route added
     * to the controller and forgotten in {@code SecurityConfiguration} fails here rather than
     * shipping open. The catch-all {@code anyRequest().authenticated()} rule covers it, but
     * "covered by a catch-all" is exactly the reasoning that stops being true after one
     * {@code permitAll} is added for something unrelated.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/submissions",
            "/submissions/new",
            "/submissions/00000000-0000-0000-0000-000000000000",
            "/submissions/00000000-0000-0000-0000-000000000000/file"})
    void redirectsAnonymousVisitorsToTheLoginPage(String path) throws Exception {
        // The exact location, not a `**/login` pattern: Spring Security's entry point writes
        // `/login` here, and `redirectedUrlPattern` matches the whole value against an Ant
        // pattern. `/login` has no leading slash to consume, so `**/login` never matches it —
        // the pattern form failed for every one of these cases while the redirect itself was
        // correct. Asserting the literal value also states the destination precisely.
        mockMvc.perform(get(path))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /**
     * An upload posted with nobody signed in is refused before a byte is examined.
     *
     * <p>The CSRF token is supplied so that the request reaches the authorisation rule rather than
     * being stopped by the CSRF filter first. Without it, this would assert the CSRF behaviour and
     * never test the route rule at all.
     */
    @Test
    void refusesAnUploadFromAnAnonymousVisitor() throws Exception {
        mockMvc.perform(multipart("/submissions")
                        .file(new MockMultipartFile("file", "essay.pdf", PDF, PDF_BYTES))
                        .param("title", "Essay one")
                        .param("courseCode", "CS101")
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    /**
     * The same request without the token is refused earlier, by the CSRF filter.
     *
     * <p>Worth asserting because the two refusals come from different layers and a future
     * reordering of the filter chain would change which one a client sees. Either way the request
     * is refused; this records which one refuses it today.
     */
    @Test
    void refusesAnUploadThatCarriesNoCsrfToken() throws Exception {
        mockMvc.perform(multipart("/submissions")
                        .file(new MockMultipartFile("file", "essay.pdf", PDF, PDF_BYTES))
                        .param("title", "Essay one")
                        .param("courseCode", "CS101"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------------
    // Student isolation
    // ------------------------------------------------------------------------

    /**
     * A student cannot open another student's submission — and is told nothing about it.
     */
    @Test
    void refusesToShowOneStudentASubmissionBelongingToAnother() throws Exception {
        SignedIn first = signedInWithSubmission("isolation-owner", "First student's essay");
        SignedIn second = signedInWithSubmission("isolation-other", "Second student's essay");

        mockMvc.perform(get("/submissions/" + first.submissionId()).session(second.session()))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("First student's essay"))));

        mockMvc.perform(get("/submissions/" + second.submissionId()).session(first.session()))
                .andExpect(status().isNotFound());
    }

    /**
     * The owner is not merely refused the other's submission — it is not loaded. The list is the
     * place a leak would be quiet and complete, so both the presence of the student's own work and
     * the absence of anyone else's are asserted in the same response.
     */
    @Test
    void listsOnlyTheSignedInStudentsOwnSubmissions() throws Exception {
        SignedIn first = signedInWithSubmission("list-owner", "Only mine");
        SignedIn second = signedInWithSubmission("list-other", "Absolutely not mine");

        assertThat(first.submissionId()).isNotEqualTo(second.submissionId());

        mockMvc.perform(get("/submissions").session(first.session()))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/list"))
                .andExpect(content().string(containsString("Only mine")))
                .andExpect(content().string(not(containsString("Absolutely not mine"))));
    }

    /**
     * Asking for an identifier that does not exist gives the same answer as asking for one that
     * belongs to somebody else — which is the point of the previous test, asserted from the other
     * direction.
     */
    @Test
    void answersNotFoundForAnIdentifierNobodyHolds() throws Exception {
        SignedIn student = signedInWithSubmission("missing-id", "Something");

        mockMvc.perform(get("/submissions/" + UUID.randomUUID()).session(student.session()))
                .andExpect(status().isNotFound());
    }

    /**
     * A path that is not an identifier at all is a bad request, not a 404 and not a 500.
     *
     * <p>It is a 400 because the framework cannot convert the path variable, before any query runs.
     * Asserting it keeps a future change from turning a malformed URL into a stack trace — the
     * failure mode that leaks the most.
     */
    @Test
    void answersBadRequestForAMalformedIdentifier() throws Exception {
        SignedIn student = signedInWithSubmission("malformed-id", "Something");

        mockMvc.perform(get("/submissions/not-an-identifier").session(student.session()))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------------
    // The pages a signed-in student does reach
    // ------------------------------------------------------------------------

    @Test
    void servesTheUploadFormToASignedInStudent() throws Exception {
        SignedIn student = signIn("access-upload-form");

        mockMvc.perform(get("/submissions/new").session(student.session()))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/upload"));
    }

    /** A student who has handed nothing in sees an empty list, not an error. */
    @Test
    void servesAnEmptyListToAStudentWhoHasSubmittedNothing() throws Exception {
        SignedIn student = signIn("access-empty-list");

        mockMvc.perform(get("/submissions").session(student.session()))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/list"));
    }

    @Test
    void servesAStudentsOwnSubmissionToThatStudent() throws Exception {
        SignedIn student = signedInWithSubmission("access-own-detail", "My own work");

        mockMvc.perform(get("/submissions/" + student.submissionId()).session(student.session()))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/detail"))
                .andExpect(content().string(containsString("My own work")));
    }

    /**
     * The detail page must not tell a student where the file lives on the server.
     *
     * <p>The storage identifier is a filesystem name. It is not a credential — the volume is not
     * servable, so knowing it fetches nothing — but it is internals, and a page has no reason to
     * disclose them ({@code CLAUDE.md} §7 rule 11).
     */
    @Test
    void doesNotDiscloseTheStoredFileNameOnTheDetailPage() throws Exception {
        SignedIn student = signedInWithSubmission("access-no-stored-name", "My own work");

        MvcResult page = mockMvc.perform(
                        get("/submissions/" + student.submissionId()).session(student.session()))
                .andExpect(status().isOk())
                .andReturn();

        String body = page.getResponse().getContentAsString();
        assertThat(student.storedFileName()).isNotBlank();
        assertThat(body).doesNotContain(student.storedFileName());
    }

    // ------------------------------------------------------------------------
    // Downloading (FR-13)
    // ------------------------------------------------------------------------

    /**
     * A student can download their own file, and it is served the way a stored PDF must be.
     *
     * <p>The headers are the assertion, not the bytes: a file rendered {@code inline} executes in
     * this application's origin, which is why the response must be an {@code attachment} with a
     * fixed media type ({@code CLAUDE.md} §7 rule 10). The body is checked too, so a 200 with an
     * empty file does not pass.
     */
    @Test
    void servesAStudentsOwnFileAsAnAttachment() throws Exception {
        SignedIn student = signedInWithSubmission("download-own", "My own work");

        mockMvc.perform(get("/submissions/" + student.submissionId() + "/file")
                        .session(student.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition",
                        containsString("download-own.pdf")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(PDF))
                .andExpect(content().bytes(PDF_BYTES));
    }

    /**
     * A student cannot download another student's file, and the answer is the same 404 as for a
     * submission they cannot see. The download inherits the ownership rule from the query that
     * resolves the submission; it is asserted separately so a future change to the download route
     * cannot quietly bypass it.
     */
    @Test
    void refusesToDownloadAnotherStudentsFile() throws Exception {
        SignedIn owner = signedInWithSubmission("download-owner", "First student's essay");
        SignedIn other = signedInWithSubmission("download-other", "Second student's essay");

        mockMvc.perform(get("/submissions/" + owner.submissionId() + "/file")
                        .session(other.session()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** Signs in as a fresh student and hands in one submission. */
    private SignedIn signedInWithSubmission(String slug, String title) throws Exception {
        SignedIn student = signIn(slug);

        MvcResult result = mockMvc.perform(multipart("/submissions")
                        .file(new MockMultipartFile("file", slug + ".pdf", PDF, PDF_BYTES))
                        .param("title", title)
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(student.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/submissions/*"))
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        UUID submissionId = UUID.fromString(location.substring("/submissions/".length()));

        String storedFileName = jdbcTemplate.queryForObject(
                "SELECT stored_file_name FROM submissions WHERE id = ?",
                String.class,
                submissionId.toString());

        return new SignedIn(student.studentId(), student.session(), submissionId, storedFileName);
    }

    private SignedIn signIn(String slug) throws Exception {
        String email = slug + "@example.com";
        if (!userService.existsByEmail(email)) {
            userService.create(email, PASSWORD, Role.STUDENT);
        }

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .param("email", email)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        return new SignedIn(userService.findByEmail(email).orElseThrow().getId(), session, null, null);
    }

    /**
     * A signed-in student, the session that proves it, and — once one has been handed in — the
     * submission that resulted.
     *
     * <p>{@code storedFileName} is read back from the row rather than captured from the upload, so
     * the disclosure test compares the rendered page against the identifier that is genuinely in
     * the database.
     */
    private record SignedIn(UUID studentId, MockHttpSession session, UUID submissionId,
                            String storedFileName) {
    }
}
