package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.support.SubmissionTestData;
import com.vit.assignmentportal.support.TestCategories;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The lifecycle rules of a submission, on the entity that owns them.
 *
 * <p>A decision is final (FR-20). That rule is enforced by {@link Submission#approve} and
 * {@link Submission#reject} rather than by a controller, so a caller that never went through the web
 * layer — a second service, a future scheduled job — cannot move a decided row. This class is where
 * that property is proved, and it is a unit test because the behaviour is a function of the entity's
 * own state and nothing else: no Spring context, no database, no clock.
 *
 * <p>The decisions take their instants as arguments, which is what makes a unit test of them
 * possible at all: there is no wall clock to substitute, because the entity never reads one. A
 * timestamp asserted below is the value the test handed in, not a value the entity happened to
 * produce.
 *
 * <p>The three refusal cases are one rule seen from three angles — a second approval, an approval
 * after a rejection, a rejection after an approval — and each is asserted separately because they
 * are the three ways a reviewer could reach it. Each also states the property that a <em>refused</em>
 * transition changed nothing, which a check on the exception type alone would not.
 */
@Tag(TestCategories.UNIT)
class SubmissionTest {

    private static final UUID STUDENT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REVIEWER_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-02T09:00:00Z");
    private static final Instant DECIDED_AT = Instant.parse("2026-10-03T11:30:00Z");

    /**
     * A fresh submission is {@code SUBMITTED}, records when it arrived on both timestamps, and holds
     * no decision at all.
     *
     * <p>The absence of a decision is asserted field by field rather than left implicit: a reviewer
     * identifier or a review time on an undecided row is the value {@code ck_submissions_decision}
     * exists to catch, and asserting only the status would not notice it.
     */
    @Test
    void createsTheRowInSubmittedWithNoDecisionYet() {
        Submission submission = SubmissionTestData.draft(STUDENT_ID, SUBMITTED_AT);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(submission.getCreatedAt()).isEqualTo(SUBMITTED_AT);
        assertThat(submission.getUpdatedAt())
                .as("a row that was just created was just changed")
                .isEqualTo(SUBMITTED_AT);
        assertThat(submission.getReviewedAt()).isNull();
        assertThat(submission.getReviewerId()).isNull();
        assertThat(submission.getRejectionReason()).isNull();
    }

    /**
     * An approval sets the status, the reviewer, and the time together.
     *
     * <p>Together is the requirement, not a convenience: the schema's {@code ck_submissions_decision}
     * refuses a decided row that is missing either the time or the reviewer, so a method that set the
     * status and left one of the other two unset would be a method the database rejects. Asserting
     * all three here is what makes that method's contract visible before the database has to say so.
     */
    @Test
    void approveRecordsTheDecisionTheReviewerAndTheTime() {
        Submission submission = SubmissionTestData.draft(STUDENT_ID, SUBMITTED_AT);

        submission.approve(REVIEWER_ID, DECIDED_AT);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(submission.getReviewerId()).isEqualTo(REVIEWER_ID);
        assertThat(submission.getReviewedAt()).isEqualTo(DECIDED_AT);
        assertThat(submission.getUpdatedAt())
                .as("the last change is the decision")
                .isEqualTo(DECIDED_AT);
        assertThat(submission.getRejectionReason())
                .as("an approved row must not carry a rejection reason")
                .isNull();
    }

    /** A rejection records the reason the student will read, beside the same three decision fields. */
    @Test
    void rejectRecordsTheReasonAlongsideTheDecision() {
        Submission submission = SubmissionTestData.draft(STUDENT_ID, SUBMITTED_AT);

        submission.reject(REVIEWER_ID, "The third question is unanswered.", DECIDED_AT);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
        assertThat(submission.getReviewerId()).isEqualTo(REVIEWER_ID);
        assertThat(submission.getReviewedAt()).isEqualTo(DECIDED_AT);
        assertThat(submission.getRejectionReason()).isEqualTo("The third question is unanswered.");
    }

    /**
     * A second approval is refused, and the first one still stands.
     *
     * <p>The second assertion is the one that matters. A guard that threw <em>after</em> mutating
     * would pass a test that checked only the exception, and would have overwritten the original
     * reviewer and time on the way out.
     */
    @Test
    void refusesASecondApprovalAndLeavesTheFirstDecisionIntact() {
        Submission submission = SubmissionTestData.draft(STUDENT_ID, SUBMITTED_AT);
        submission.approve(REVIEWER_ID, DECIDED_AT);
        UUID otherReviewer = UUID.fromString("33333333-3333-3333-3333-333333333333");
        Instant later = DECIDED_AT.plusSeconds(60);

        assertThatThrownBy(() -> submission.approve(otherReviewer, later))
                .isInstanceOf(SubmissionAlreadyDecidedException.class);

        assertThat(submission.getReviewerId()).isEqualTo(REVIEWER_ID);
        assertThat(submission.getReviewedAt()).isEqualTo(DECIDED_AT);
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
    }

    /** Rejected work cannot be approved: a decision is terminal in both directions (FR-20). */
    @Test
    void refusesAnApprovalOfAlreadyRejectedWork() {
        Submission submission = SubmissionTestData.draft(STUDENT_ID, SUBMITTED_AT);
        submission.reject(REVIEWER_ID, "Not a readable PDF.", DECIDED_AT);

        assertThatThrownBy(() -> submission.approve(REVIEWER_ID, DECIDED_AT.plusSeconds(60)))
                .isInstanceOf(SubmissionAlreadyDecidedException.class);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.REJECTED);
        assertThat(submission.getRejectionReason()).isEqualTo("Not a readable PDF.");
    }

    /** Approved work cannot be rejected: the reviewer cannot change their mind after the fact. */
    @Test
    void refusesARejectionOfAlreadyApprovedWork() {
        Submission submission = SubmissionTestData.draft(STUDENT_ID, SUBMITTED_AT);
        submission.approve(REVIEWER_ID, DECIDED_AT);

        assertThatThrownBy(() -> submission.reject(REVIEWER_ID, "Changed my mind",
                DECIDED_AT.plusSeconds(60)))
                .isInstanceOf(SubmissionAlreadyDecidedException.class);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.APPROVED);
        assertThat(submission.getRejectionReason()).isNull();
    }

    /**
     * A rejection with no reason is unrepresentable, not merely refused by the review service.
     *
     * <p>The check exists in two places on purpose: the service's produces a sentence for the
     * reviewer, and this one makes a reason-less rejection impossible for any caller. This test pins
     * the second — the one no route can bypass.
     */
    @Test
    void refusesARejectionWithoutAReason() {
        Submission submission = SubmissionTestData.draft(STUDENT_ID, SUBMITTED_AT);

        assertThatThrownBy(() -> submission.reject(REVIEWER_ID, null, DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason");

        assertThat(submission.getStatus())
                .as("a refused rejection leaves the submission where it was")
                .isEqualTo(SubmissionStatus.SUBMITTED);
    }

    /** Whitespace is the empty reason with different characters. */
    @Test
    void refusesARejectionWhoseReasonIsOnlyWhitespace() {
        Submission submission = SubmissionTestData.draft(STUDENT_ID, SUBMITTED_AT);

        assertThatThrownBy(() -> submission.reject(REVIEWER_ID, "   ", DECIDED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SUBMITTED);
    }
}
