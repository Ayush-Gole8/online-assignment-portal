package com.vit.assignmentportal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The reviewer workflow, end to end through the web layer: a student submits, a reviewer decides,
 * and both sides see the result — with the history recorded at every step (FR-15…FR-21).
 *
 * <p>MockMvc rather than a service call, because most of what this milestone must prove is a
 * boundary rather than a computation: that a {@code STUDENT} is refused at {@code /review}, that an
 * anonymous visitor is redirected, that a rejection without a reason changes nothing, that the
 * controller turns a refusal into a message rather than a stack trace. None of those are visible
 * from {@code ReviewService} alone.
 *
 * <p>Both accounts are signed in for real — the session comes from {@code POST /login}, not from a
 * principal placed in the security context. As {@code AuthorizationIT} explains, that is what proves
 * the whole chain from {@code DatabaseUserDetailsService} to {@code hasAnyRole} rather than just the
 * route rule in isolation.
 *
 * <p>Every assertion about what was <em>written</em> goes through {@link JdbcTemplate} rather than
 * JPA. The question is what the database actually holds after the request, and reading it back
 * through the same ORM that wrote it would not answer that.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReviewWorkflowIT extends MySqlIntegrationTest {

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
    // The queue
    // ------------------------------------------------------------------------

    /**
     * A freshly submitted artefact appears in the queue, and one that has been decided does not.
     *
     * <p>Both halves in one test on purpose: "the queue shows submitted work" passes trivially if
     * the query returned everything, so the negative case is what gives the assertion meaning.
     */
    @Test
    void listsSubmittedWorkAndOnlySubmittedWork() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "queue");
        submitAsStudent("queue-awaiting", "Still waiting");
        UUID decided = submitAsStudent("queue-decided", "Already handled");

        decide(reviewer.session(), decided, "APPROVED", null)
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/review").session(reviewer.session()))
                .andExpect(status().isOk())
                .andExpect(view().name("review/queue"))
                .andExpect(content().string(containsString("Still waiting")))
                .andExpect(content().string(not(containsString("Already handled"))));
    }

    /** A reviewer can open one submission and its artefact (FR-17). */
    @Test
    void showsOneSubmissionToAReviewer() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "detail");
        UUID submissionId = submitAsStudent("detail-owner", "Openable work");

        mockMvc.perform(get("/review/submissions/" + submissionId).session(reviewer.session()))
                .andExpect(status().isOk())
                .andExpect(view().name("review/detail"))
                .andExpect(content().string(containsString("Openable work")));
    }

    // ------------------------------------------------------------------------
    // Approval
    // ------------------------------------------------------------------------

    /**
     * An approval moves the submission to {@code APPROVED}, records the reviewer and the time, and
     * appends exactly one history row.
     *
     * <p>The history row is asserted as well as the status column: a decision that changed the
     * submission but left no audit trail is the failure this milestone is meant to make impossible,
     * and it would pass a test that only read the {@code status} column.
     */
    @Test
    void approvesASubmission() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "approve");
        UUID submissionId = submitAsStudent("approve-owner", "Approvable work");

        decide(reviewer.session(), submissionId, "APPROVED", null)
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/review/submissions/" + submissionId));

        Map<String, Object> row = submissionRow(submissionId);
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("reviewed_at")).isNotNull();
        assertThat(row.get("reviewer_id")).isEqualTo(reviewer.userId().toString());
        assertThat(row.get("rejection_reason")).isNull();

        List<Map<String, Object>> history = historyRows(submissionId);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("from_status")).isNull();
        assertThat(history.get(0).get("to_status")).isEqualTo("SUBMITTED");
        assertThat(history.get(1).get("from_status")).isEqualTo("SUBMITTED");
        assertThat(history.get(1).get("to_status")).isEqualTo("APPROVED");
        assertThat(history.get(1).get("actor_id")).isEqualTo(reviewer.userId().toString());
        assertThat(history.get(1).get("note")).isNull();
    }

    // ------------------------------------------------------------------------
    // Rejection
    // ------------------------------------------------------------------------

    /** A rejection stores the status, the reviewer, and the reason the student will read (FR-19). */
    @Test
    void rejectsASubmissionWithAReason() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "reject");
        UUID submissionId = submitAsStudent("reject-owner", "Rejectable work");

        decide(reviewer.session(), submissionId, "REJECTED", "Not a readable PDF.")
                .andExpect(status().is3xxRedirection());

        Map<String, Object> row = submissionRow(submissionId);
        assertThat(row.get("status")).isEqualTo("REJECTED");
        assertThat(row.get("reviewer_id")).isEqualTo(reviewer.userId().toString());
        assertThat(row.get("rejection_reason")).isEqualTo("Not a readable PDF.");

        List<Map<String, Object>> history = historyRows(submissionId);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).get("to_status")).isEqualTo("REJECTED");
        assertThat(history.get(1).get("note")).isEqualTo("Not a readable PDF.");
    }

    /**
     * A rejection with no reason is refused, and — the part that matters — nothing is written.
     *
     * <p>Asserting only the error message would pass even if the status had changed and the reason
     * had been stored as null; the row and the history are checked to prove the refusal was total.
     */
    @Test
    void refusesARejectionWithNoReason() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "no-reason");
        UUID submissionId = submitAsStudent("no-reason-owner", "Needs a reason");

        decide(reviewer.session(), submissionId, "REJECTED", null)
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("reason")));

        assertThat(submissionRow(submissionId).get("status")).isEqualTo("SUBMITTED");
        assertThat(historyRows(submissionId)).hasSize(1);
    }

    /** Whitespace is not a reason. A blank field is the empty field with different characters. */
    @Test
    void refusesARejectionWithABlankReason() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "blank-reason");
        UUID submissionId = submitAsStudent("blank-reason-owner", "Needs a reason");

        decide(reviewer.session(), submissionId, "REJECTED", "   ")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("reason")));

        assertThat(submissionRow(submissionId).get("status")).isEqualTo("SUBMITTED");
        assertThat(historyRows(submissionId)).hasSize(1);
    }

    /**
     * An approval that carries a rejection reason is refused.
     *
     * <p>The reason column is rendered to the student under a heading that names rejection, so text
     * stored there against approved work would contradict the status above it. Refused rather than
     * silently dropped, because a reviewer who typed it meant something.
     */
    @Test
    void refusesAnApprovalThatCarriesAReason() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "approve-with-note");
        UUID submissionId = submitAsStudent("approve-note-owner", "Approvable work");

        decide(reviewer.session(), submissionId, "APPROVED", "Looks fine but here is a note")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("cannot carry a rejection")));

        assertThat(submissionRow(submissionId).get("status")).isEqualTo("SUBMITTED");
        assertThat(historyRows(submissionId)).hasSize(1);
    }

    // ------------------------------------------------------------------------
    // Terminality (FR-20)
    // ------------------------------------------------------------------------

    /**
     * A decided submission cannot be decided again — the first decision stands, and no second
     * history row appears.
     *
     * <p>This is the case the terminality rule exists for, tested from the outside: the second
     * request is a perfectly well-formed rejection, and it is the <em>state</em> of the submission
     * that refuses it. The check is in the service and the entity, not in the view, which is why the
     * request is sent directly rather than by rendering the form and finding no button.
     */
    @Test
    void refusesASecondDecision() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "second-decision");
        UUID submissionId = submitAsStudent("second-decision-owner", "Decided once");

        decide(reviewer.session(), submissionId, "APPROVED", null)
                .andExpect(status().is3xxRedirection());

        decide(reviewer.session(), submissionId, "REJECTED", "Changed my mind")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("final")));

        assertThat(submissionRow(submissionId).get("status")).isEqualTo("APPROVED");
        assertThat(submissionRow(submissionId).get("rejection_reason")).isNull();
        assertThat(historyRows(submissionId)).hasSize(2);
    }

    /**
     * A second reviewer is refused too, and their attempt is not recorded.
     *
     * <p>Distinct from the case above: there the same reviewer tried twice, here a different
     * reviewer works from a queue that was stale. Both must be refused, and the failure must not
     * overwrite the first reviewer's decision.
     */
    @Test
    void refusesASecondDecisionByAnotherReviewer() throws Exception {
        SignedIn first = signInAs(Role.REVIEWER, "first-reviewer");
        SignedIn second = signInAs(Role.REVIEWER, "second-reviewer");
        UUID submissionId = submitAsStudent("two-reviewers-owner", "Decided by one");

        decide(first.session(), submissionId, "APPROVED", null)
                .andExpect(status().is3xxRedirection());

        decide(second.session(), submissionId, "REJECTED", "I disagree")
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("final")));

        Map<String, Object> row = submissionRow(submissionId);
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("reviewer_id")).isEqualTo(first.userId().toString());
        assertThat(historyRows(submissionId)).hasSize(2);
    }

    // ------------------------------------------------------------------------
    // Malformed decisions
    // ------------------------------------------------------------------------

    /** No outcome at all is a client mistake, not a 400 that names a missing parameter. */
    @Test
    void refusesADecisionWithNoOutcome() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "no-outcome");
        UUID submissionId = submitAsStudent("no-outcome-owner", "Decidable work");

        mockMvc.perform(post("/review/submissions/" + submissionId)
                        .with(csrf())
                        .session(reviewer.session()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("approve or reject")));

        assertThat(submissionRow(submissionId).get("status")).isEqualTo("SUBMITTED");
    }

    /**
     * {@code SUBMITTED} is a status but not a decision, and a form field can carry it.
     *
     * <p>The check is in the service because the enum binds it happily: {@code SUBMITTED} is a valid
     * {@code SubmissionStatus}, so only a rule that knows which statuses are decisions refuses it.
     */
    @Test
    void refusesSubmittedAsAnOutcome() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "submitted-outcome");
        UUID submissionId = submitAsStudent("submitted-outcome-owner", "Decidable work");

        decide(reviewer.session(), submissionId, "SUBMITTED", null)
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", containsString("approval or a rejection")));

        assertThat(submissionRow(submissionId).get("status")).isEqualTo("SUBMITTED");
        assertThat(historyRows(submissionId)).hasSize(1);
    }

    // ------------------------------------------------------------------------
    // Authorisation
    // ------------------------------------------------------------------------

    /** A STUDENT is refused every reviewer route, read and write alike. */
    @Test
    void refusesAStudentEveryReviewRoute() throws Exception {
        SignedIn student = signInAs(Role.STUDENT, "student-review-route");
        UUID submissionId = submitAsStudent("student-review-owner", "Not yours to review");

        mockMvc.perform(get("/review").session(student.session()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/review/submissions/" + submissionId).session(student.session()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/review/submissions/" + submissionId + "/file")
                        .session(student.session()))
                .andExpect(status().isForbidden());

        // The write is refused before the CSRF filter even matters — the route rule runs first,
        // and the token is supplied so the refusal is provably the role's and not the filter's.
        mockMvc.perform(post("/review/submissions/" + submissionId)
                        .with(csrf())
                        .param("outcome", "APPROVED")
                        .session(student.session()))
                .andExpect(status().isForbidden());

        assertThat(submissionRow(submissionId).get("status")).isEqualTo("SUBMITTED");
    }

    /** An anonymous visitor is sent to the login page rather than shown a 403. */
    @Test
    void redirectsAnonymousVisitorsFromTheReviewRoutes() throws Exception {
        UUID anyId = UUID.randomUUID();

        mockMvc.perform(get("/review"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/review/submissions/" + anyId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));

        mockMvc.perform(get("/review/submissions/" + anyId + "/file"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ------------------------------------------------------------------------
    // What the student sees (FR-14, FR-21)
    // ------------------------------------------------------------------------

    /**
     * After a rejection the student sees the status, the reviewer's reason, and the chronological
     * history — on their own page, and nowhere near anybody else's.
     */
    @Test
    void showsTheDecisionAndReasonToTheOwningStudent() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "student-view");
        SignedIn owner = signInAs(Role.STUDENT, "student-view-owner");
        UUID submissionId = submitAsStudent(owner.session(), "student-view-owner", "Marked work");

        decide(reviewer.session(), submissionId, "REJECTED", "The third question is unanswered.")
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/submissions/" + submissionId).session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(view().name("submission/detail"))
                .andExpect(content().string(containsString("REJECTED")))
                .andExpect(content().string(containsString("The third question is unanswered.")))
                // The history: the creation line and the decision line, both present.
                .andExpect(content().string(containsString("Submitted for review")))
                .andExpect(content().string(containsString("Decision: REJECTED")));
    }

    /**
     * The history a student reads does not name the reviewer (FR-22 is an administrator's concern,
     * not the student's).
     *
     * <p>The reviewer's identifier is the value a later feature would use to look up the person, so
     * it must not reach a page the student can read. The kind of check that catches a leak is the
     * one on the rendered body, not on the read model.
     */
    @Test
    void doesNotDiscloseTheReviewerToTheStudent() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "no-disclosure");
        SignedIn owner = signInAs(Role.STUDENT, "no-disclosure-owner");
        UUID submissionId = submitAsStudent(owner.session(), "no-disclosure-owner", "Marked work");

        decide(reviewer.session(), submissionId, "APPROVED", null)
                .andExpect(status().is3xxRedirection());

        MvcResult page = mockMvc.perform(get("/submissions/" + submissionId).session(owner.session()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(page.getResponse().getContentAsString())
                .doesNotContain(reviewer.userId().toString())
                .doesNotContain(reviewer.email());
    }

    /** Another student still cannot open it — the decision changes nothing about ownership. */
    @Test
    void keepsTheSubmissionPrivateAfterADecision() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "still-private");
        SignedIn owner = signInAs(Role.STUDENT, "still-private-owner");
        SignedIn stranger = signInAs(Role.STUDENT, "still-private-stranger");
        UUID submissionId = submitAsStudent(owner.session(), "still-private-owner", "Private work");

        decide(reviewer.session(), submissionId, "APPROVED", null)
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/submissions/" + submissionId).session(stranger.session()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/submissions/" + submissionId + "/file").session(stranger.session()))
                .andExpect(status().isNotFound());
    }

    /**
     * An identifier nobody holds is a 404 with the shared error page, not a 500.
     *
     * <p>This is the one route where a 404 is not a privacy decision. A reviewer may open any
     * submission, so there is no ownership clause to fold "not yours" into "not found" — the
     * identifier simply resolves to nothing, and it arrives from the address bar with nothing having
     * validated it. A lookup that missed is an ordinary event; answering it with a 500, or with a
     * body that named the exception, would be the leak {@code CLAUDE.md} §7 rule 11 forbids.
     *
     * <p>The body is asserted rather than only the status, because the status alone would pass for a
     * response that explained itself too well.
     */
    @Test
    void answersNotFoundForAnIdentifierNobodyHolds() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "review-unknown-id");

        String body = mockMvc.perform(
                        get("/review/submissions/" + UUID.randomUUID()).session(reviewer.session()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("error"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .contains("Error 404")
                .doesNotContain("SubmissionNotFoundException")
                .doesNotContain("com.vit.assignmentportal");
    }

    // ------------------------------------------------------------------------
    // Download (FR-17)
    // ------------------------------------------------------------------------

    /**
     * A reviewer can download the artefact to read it, served the same way the student's copy is.
     *
     * <p>The headers are the security assertion ({@code CLAUDE.md} §7 rule 10); the bytes are the
     * proof that the response is the file and not an empty 200.
     */
    @Test
    void servesTheFileToAReviewer() throws Exception {
        SignedIn reviewer = signInAs(Role.REVIEWER, "download-reviewer");
        UUID submissionId = submitAsStudent("download-reviewer-owner", "Readable work");

        mockMvc.perform(get("/review/submissions/" + submissionId + "/file")
                        .session(reviewer.session()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().contentType(PDF))
                .andExpect(content().bytes(PDF_BYTES));
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * Posts a decision with the CSRF token, as the browser form would.
     *
     * <p>Returns the {@code ResultActions} rather than asserting inside, so each test states its own
     * expectation about the outcome — a redirect either way, but a different flash attribute.
     */
    private ResultActions decide(MockHttpSession session, UUID submissionId, String outcome,
                                 String note) throws Exception {

        var request = post("/review/submissions/" + submissionId)
                .with(csrf())
                .session(session)
                .param("outcome", outcome);

        if (note != null) {
            request = request.param("note", note);
        }

        return mockMvc.perform(request);
    }

    /** Signs in as a fresh student and hands in one submission, returning its identifier. */
    private UUID submitAsStudent(String slug, String title) throws Exception {
        SignedIn student = signInAs(Role.STUDENT, slug);
        return submitAsStudent(student.session(), slug, title);
    }

    /** Hands in one submission as the already-signed-in student, returning its identifier. */
    private UUID submitAsStudent(MockHttpSession session, String slug, String title)
            throws Exception {

        MvcResult result = mockMvc.perform(multipart("/submissions")
                        .file(new MockMultipartFile("file", slug + ".pdf", PDF, PDF_BYTES))
                        .param("title", title)
                        .param("courseCode", "CS101")
                        .with(csrf())
                        .session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("/submissions/*"))
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        return UUID.fromString(location.substring("/submissions/".length()));
    }

    /**
     * Signs in as the given role with a distinct account per {@code slug}, so that two reviewers in
     * one test are two real users rather than one account signed in twice.
     */
    private SignedIn signInAs(Role role, String slug) throws Exception {
        String email = "%s-%s@example.com".formatted(slug, role.name().toLowerCase());

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

        return new SignedIn(session, userService.findByEmail(email).orElseThrow().getId(), email);
    }

    /** The submission row's decision columns, as the database actually holds them. */
    private Map<String, Object> submissionRow(UUID submissionId) {
        return jdbcTemplate.queryForMap("""
                SELECT status, reviewed_at, reviewer_id, rejection_reason
                  FROM submissions
                 WHERE id = ?
                """, submissionId.toString());
    }

    /**
     * One submission's history rows, oldest first.
     *
     * <p>Ordered by {@code created_at}, matching the repository's ordering. The creation row and a
     * decision are written far apart in time, so the ordering is unambiguous in practice; a test that
     * needed to distinguish two rows written in the same instant would need a sequence column, which
     * the schema deliberately does not have.
     */
    private List<Map<String, Object>> historyRows(UUID submissionId) {
        return jdbcTemplate.queryForList("""
                SELECT from_status, to_status, actor_id, note
                  FROM submission_status_history
                 WHERE submission_id = ?
                 ORDER BY created_at
                """, submissionId.toString());
    }

    /** A signed-in user: the session that proves it, their identifier, and their email. */
    private record SignedIn(MockHttpSession session, UUID userId, String email) {
    }
}
