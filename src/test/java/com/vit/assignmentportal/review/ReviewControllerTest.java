package com.vit.assignmentportal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.auth.CurrentUserService;
import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.submission.SubmissionAlreadyDecidedException;
import com.vit.assignmentportal.support.TestCategories;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * The decision handler's failure paths, tested without a Spring context.
 *
 * <p>Every case here is one of the three failures {@link ReviewController#decide} catches. Two of
 * them are already proved end to end by {@code ReviewWorkflowIT} — a malformed decision and an
 * already-decided submission are both reachable through the web layer. The concurrent case is not:
 * an optimistic-lock failure needs two transactions racing, and a MockMvc request is a single
 * transaction. Rather than leave the most subtle branch untested, it is driven directly here, where
 * the exception can simply be raised.
 *
 * <p>This is also the only place the <em>messages</em> can be asserted cheaply. Each refusal has to
 * reach the reviewer as a sentence, not as a stack trace or an exception class name
 * ({@code CLAUDE.md} §7 rule 11), and each has to describe something different, because the three
 * have different remedies: fix the form, accept that the work is done, or re-read the submission.
 */
@Tag(TestCategories.UNIT)
class ReviewControllerTest {

    private static final UUID SUBMISSION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private final ReviewService reviewService = mock(ReviewService.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ReviewController controller =
            new ReviewController(reviewService, currentUserService);

    private final AuthenticatedUser reviewer = new AuthenticatedUser(
            UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
            "reviewer@example.com", "$2a$10$notarealhash", Role.REVIEWER, true);

    @Test
    void recordsAValidDecisionAndSaysSoOnTheNextPage() throws Exception {
        when(currentUserService.requireCurrentUser()).thenReturn(reviewer);
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.decide(SUBMISSION_ID, SubmissionStatus.APPROVED, null,
                redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/submissions/" + SUBMISSION_ID);
        assertThat(redirectAttributes.getFlashAttributes().get("decided"))
                .isEqualTo(SubmissionStatus.APPROVED);
        assertThat(redirectAttributes.getFlashAttributes()).doesNotContainKey("error");
    }

    /**
     * The concurrent case, and the reason the optimistic lock exists.
     *
     * <p>The message must say that somebody else's decision stands and that this one was not
     * recorded, because those are the two facts the reviewer needs. It must not name the exception
     * or the database.
     */
    @Test
    void reportsAConcurrentDecisionInPlainLanguage() throws Exception {
        when(currentUserService.requireCurrentUser()).thenReturn(reviewer);
        // No single-String constructor exists: Spring's ORM exception is built from a message and
        // the identifier of the row that was stale, so the submission's id is passed rather than
        // nothing. The message text is irrelevant to what this test asserts — ReviewController
        // composes its own sentence and never echoes the exception's — which is exactly why the
        // assertions below check that "simulated stale row" does not reach the page.
        doThrow(new ObjectOptimisticLockingFailureException("simulated stale row", SUBMISSION_ID))
                .when(reviewService).decide(eq(reviewer.id()), eq(SUBMISSION_ID), any());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        String view = controller.decide(SUBMISSION_ID, SubmissionStatus.APPROVED, null,
                redirectAttributes);

        assertThat(view).isEqualTo("redirect:/review/submissions/" + SUBMISSION_ID);

        String message = (String) redirectAttributes.getFlashAttributes().get("error");
        assertThat(message)
                .containsIgnoringCase("another reviewer")
                .containsIgnoringCase("was not recorded")
                .doesNotContain("ObjectOptimisticLockingFailureException")
                .doesNotContainIgnoringCase("exception")
                .doesNotContain("simulated stale row");
    }

    /** An already-decided submission is a fact, not an error the reviewer can fix. */
    @Test
    void reportsASubmissionThatHasAlreadyBeenDecided() throws Exception {
        when(currentUserService.requireCurrentUser()).thenReturn(reviewer);
        doThrow(SubmissionAlreadyDecidedException.forDecidedSubmission())
                .when(reviewService).decide(eq(reviewer.id()), eq(SUBMISSION_ID), any());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        controller.decide(SUBMISSION_ID, SubmissionStatus.REJECTED, "A reason", redirectAttributes);

        assertThat(redirectAttributes.getFlashAttributes().get("error"))
                .isEqualTo("This submission has already been decided, and a decision is final.");
    }

    /**
     * A malformed decision is reported, and the reviewer's typing is handed back.
     *
     * <p>The note is preserved so that a reviewer who wrote a long reason and then chose the
     * <em>other</em> outcome does not lose it to the redirect. Losing it would turn a small
     * correction into retyping the whole thing.
     */
    @Test
    void reportsAMalformedDecisionAndKeepsWhatTheReviewerWrote() throws Exception {
        when(currentUserService.requireCurrentUser()).thenReturn(reviewer);
        InvalidReviewException refusal = new InvalidReviewException("A rejection requires a reason.");
        doThrow(refusal).when(reviewService).decide(eq(reviewer.id()), eq(SUBMISSION_ID), any());

        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();
        controller.decide(SUBMISSION_ID, SubmissionStatus.REJECTED, "the note", redirectAttributes);

        assertThat(redirectAttributes.getFlashAttributes().get("error"))
                .isEqualTo("A rejection requires a reason.");
        assertThat(redirectAttributes.getFlashAttributes().get("note")).isEqualTo("the note");
    }

    /**
     * The actor on a decision is the authenticated principal, never a request parameter.
     *
     * <p>The handler signature has no reviewer field to bind, which is the design; this asserts the
     * consequence — the identifier that reaches the service is the one from
     * {@code CurrentUserService} ({@code CLAUDE.md} §7 rule 8).
     */
    @Test
    void takesTheReviewerFromTheSessionRatherThanTheRequest() throws Exception {
        when(currentUserService.requireCurrentUser()).thenReturn(reviewer);

        controller.decide(SUBMISSION_ID, SubmissionStatus.APPROVED, null,
                new RedirectAttributesModelMap());

        verify(reviewService).decide(eq(reviewer.id()), eq(SUBMISSION_ID), any());
    }
}
