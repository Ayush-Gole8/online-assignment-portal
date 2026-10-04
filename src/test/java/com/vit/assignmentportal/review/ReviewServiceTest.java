package com.vit.assignmentportal.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.submission.DownloadableFile;
import com.vit.assignmentportal.submission.SubmissionDetail;
import com.vit.assignmentportal.submission.SubmissionHistoryEntry;
import com.vit.assignmentportal.submission.SubmissionService;
import com.vit.assignmentportal.submission.SubmissionSummary;
import com.vit.assignmentportal.support.SubmissionTestData;
import com.vit.assignmentportal.support.TestCategories;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The reviewer's decision <em>policy</em>, at the layer that owns it.
 *
 * <p>{@link ReviewService} does not change a submission. It decides whether a decision is one this
 * application accepts, and hands the acceptable ones to {@link SubmissionService}, which owns the
 * transition and the history. So this test does not look at any state transition — there is none
 * here to look at — it looks at which decisions are passed through and which are refused, and at the
 * property that a refusal reaches the submission service not at all.
 *
 * <p>That last property is the reason {@link SubmissionService} is a mock rather than a real
 * instance: {@code verifyNoInteractions} is the assertion that a refused decision was refused
 * <em>before</em> anything was written, and it can only be made against a collaborator whose
 * interactions are observed. The value of the refusal — the sentence a reviewer reads — is asserted
 * by {@code ReviewWorkflowIT}, through the page; what is asserted here is the rule itself.
 */
@Tag(TestCategories.UNIT)
class ReviewServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-02T09:00:00Z");
    private static final UUID REVIEWER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUBMISSION_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final SubmissionService submissionService = mock(SubmissionService.class);
    private final ReviewService reviewService = new ReviewService(submissionService);

    // ------------------------------------------------------------------------
    // Reads: delegations through the feature that owns the row
    // ------------------------------------------------------------------------

    /**
     * Every read a reviewer makes goes through {@link SubmissionService}.
     *
     * <p>{@code review} may not import a submission repository or an entity
     * (docs/architecture/application-structure.md §4 rule 1), which is why these are delegations and
     * not queries. The test states that as behaviour: the identifier a route supplies reaches the
     * service unchanged, and the value it returns is the value handed back.
     */
    @Test
    void routesEveryReadThroughTheSubmissionService() {
        Pageable page = PageRequest.of(0, 20);
        Page<SubmissionSummary> queue = Page.empty(page);
        SubmissionDetail detail = SubmissionDetail.from(SubmissionTestData.draft(REVIEWER_ID, NOW));
        List<SubmissionHistoryEntry> history = List.of();
        DownloadableFile file = new DownloadableFile("essay.pdf", "application/pdf", new byte[] {1});

        when(submissionService.listAwaitingReview(page)).thenReturn(queue);
        when(submissionService.detailForReview(SUBMISSION_ID)).thenReturn(detail);
        when(submissionService.historyForReview(SUBMISSION_ID)).thenReturn(history);
        when(submissionService.downloadForReview(SUBMISSION_ID)).thenReturn(file);

        assertThat(reviewService.queue(page)).isSameAs(queue);
        assertThat(reviewService.view(SUBMISSION_ID)).isSameAs(detail);
        assertThat(reviewService.history(SUBMISSION_ID)).isSameAs(history);
        assertThat(reviewService.download(SUBMISSION_ID)).isSameAs(file);
    }

    // ------------------------------------------------------------------------
    // Decisions that are accepted
    // ------------------------------------------------------------------------

    /** An approval is passed through with no note, which is the only note an approval may carry. */
    @Test
    void passesAnApprovalThroughWithNoNote() {
        reviewService.decide(REVIEWER_ID, SUBMISSION_ID,
                new ReviewDecision(SubmissionStatus.APPROVED, null));

        verify(submissionService).recordDecision(REVIEWER_ID, SUBMISSION_ID,
                SubmissionStatus.APPROVED, null);
    }

    /**
     * A rejection is passed through with its reason, trimmed.
     *
     * <p>The trim is asserted because the reason is stored and shown to the student: a leading space
     * that survived to the column would be rendered as one, and the column's width is measured on the
     * stored value, so trimming also decides whether a reason of exactly the limit is accepted.
     */
    @Test
    void passesARejectionThroughWithItsTrimmedReason() {
        reviewService.decide(REVIEWER_ID, SUBMISSION_ID,
                new ReviewDecision(SubmissionStatus.REJECTED, "  The third question is unanswered.  "));

        verify(submissionService).recordDecision(REVIEWER_ID, SUBMISSION_ID,
                SubmissionStatus.REJECTED, "The third question is unanswered.");
    }

    /**
     * A reason of exactly the column's width is accepted.
     *
     * <p>The boundary that proves the limit is a maximum rather than a bound that refuses the last
     * admissible value. The value is built rather than pasted so the length is unambiguous.
     */
    @Test
    void acceptsAReasonOfExactlyTheMaximumLength() {
        String reason = "x".repeat(2000);

        reviewService.decide(REVIEWER_ID, SUBMISSION_ID,
                new ReviewDecision(SubmissionStatus.REJECTED, reason));

        verify(submissionService).recordDecision(REVIEWER_ID, SUBMISSION_ID,
                SubmissionStatus.REJECTED, reason);
    }

    // ------------------------------------------------------------------------
    // Decisions that are refused, and the property that nothing is written
    // ------------------------------------------------------------------------

    /** No decision at all — a decision object whose outcome is absent. */
    @Test
    void refusesADecisionWithNoOutcome() {
        assertRefused(new ReviewDecision(null, null));
    }

    /** No decision object at all. */
    @Test
    void refusesANullDecision() {
        assertRefused(null);
    }

    /**
     * {@code SUBMITTED} is a status but not a decision, and a form field can carry it.
     *
     * <p>The rule lives on {@code SubmissionStatus.isDecision} rather than as a pair of literals, and
     * this is the case that would slip through if it did not: {@code SUBMITTED} binds as a
     * {@code SubmissionStatus} perfectly well.
     */
    @Test
    void refusesAnOutcomeThatIsNotADecision() {
        assertRefused(new ReviewDecision(SubmissionStatus.SUBMITTED, null));
    }

    /** A rejection with no reason is refused (FR-19). */
    @Test
    void refusesARejectionWithoutAReason() {
        assertRefused(new ReviewDecision(SubmissionStatus.REJECTED, null));
    }

    /** Whitespace is not a reason. */
    @Test
    void refusesARejectionWhoseReasonIsOnlyWhitespace() {
        assertRefused(new ReviewDecision(SubmissionStatus.REJECTED, "   "));
    }

    /**
     * An approval that carries a reason is refused.
     *
     * <p>The column is {@code rejection_reason}, so text stored there against approved work would be
     * shown to the student under a heading that contradicts their own status. Refused rather than
     * silently dropped, because a reviewer who typed it meant something.
     */
    @Test
    void refusesAnApprovalThatCarriesAReason() {
        assertRefused(new ReviewDecision(SubmissionStatus.APPROVED, "Looks fine but a note"));
    }

    /** A reason wider than the column is refused rather than truncated. */
    @Test
    void refusesAReasonLongerThanTheColumnCanHold() {
        assertRefused(new ReviewDecision(SubmissionStatus.REJECTED, "x".repeat(2001)));
    }

    // ------------------------------------------------------------------------
    // Support
    // ------------------------------------------------------------------------

    /**
     * Asserts that the decision is refused with a {@link InvalidReviewException} and that the
     * submission service was never touched.
     *
     * <p>Both halves in one helper because they are one property: a policy refusal that still reached
     * the service would be a refusal that wrote something. Every case below is the same assertion
     * with a different input, which is exactly the shape the rule has.
     */
    private void assertRefused(ReviewDecision decision) {
        assertThatThrownBy(() -> reviewService.decide(REVIEWER_ID, SUBMISSION_ID, decision))
                .isInstanceOf(InvalidReviewException.class);

        verifyNoInteractions(submissionService);
    }
}
