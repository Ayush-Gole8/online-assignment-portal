package com.vit.assignmentportal.review;

import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.submission.DownloadableFile;
import com.vit.assignmentportal.submission.Submission;
import com.vit.assignmentportal.submission.SubmissionDetail;
import com.vit.assignmentportal.submission.SubmissionHistoryEntry;
import com.vit.assignmentportal.submission.SubmissionService;
import com.vit.assignmentportal.submission.SubmissionSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reviewer's side of the workflow: the queue, one submission, and the decision (FR-16 to
 * FR-21).
 *
 * <h2>What this class owns, and what it deliberately does not</h2>
 *
 * <p>It owns the <strong>decision policy</strong> — which outcomes are decisions at all, and the
 * rules relating an outcome to its note. It does <strong>not</strong> own the state transition. The
 * status change, the two decision columns, and the history row are written by
 * {@code SubmissionService.recordDecision}, inside one transaction.
 *
 * <p>That division is required rather than chosen. {@code review} may not import
 * {@code SubmissionRepository} and may not mutate a {@code Submission} directly
 * (docs/architecture/application-structure.md §4 rule 1), which is exactly what makes the
 * terminality rule enforceable in one place instead of in every feature that might one day want to
 * change a status. So this class holds no repository, and the only way it changes anything is by
 * calling the service that owns the entity.
 *
 * <p>It also performs <strong>no authorisation</strong>. Every route that reaches these methods is
 * behind {@code /review/**}, which {@code SecurityConfiguration} restricts to {@code REVIEWER} and
 * {@code ADMIN}. A role check repeated here would be a second copy of the policy that could drift
 * from the first; the single copy lives in the filter chain and is asserted by the integration
 * tests. What this class does take is the reviewer's identifier, from the authenticated principal,
 * so the recorded actor is never a value the request supplied ({@code CLAUDE.md} §7 rule 8).
 */
@Service
public class ReviewService {

    /**
     * The width of both {@code submissions.rejection_reason} and
     * {@code submission_status_history.note}, which hold the same text.
     */
    private static final int MAX_NOTE_LENGTH = 2000;

    private final SubmissionService submissionService;

    public ReviewService(SubmissionService submissionService) {
        this.submissionService = submissionService;
    }

    /**
     * One page of the queue: submissions still awaiting a decision, longest-waiting first (FR-16).
     *
     * <p>Read models, not entities — the queue is rendered by a template, and
     * {@link SubmissionSummary} is the type that exists so a view cannot reach a field it should
     * not render. The mapping happens after the page is read, so only the rows on this page are
     * projected; the total the page carries is still the count of the whole queue, which is what
     * the pager needs.
     */
    @Transactional(readOnly = true)
    public Page<SubmissionSummary> queue(Pageable pageable) {
        return submissionService.listAwaitingReview(pageable).map(SubmissionSummary::from);
    }

    /**
     * One submission, as a reviewer sees it (FR-17).
     *
     * @throws com.vit.assignmentportal.submission.SubmissionNotFoundException if there is no such
     *                                                                        submission
     */
    @Transactional(readOnly = true)
    public SubmissionDetail view(UUID submissionId) {
        return SubmissionDetail.from(submissionService.findForReview(submissionId));
    }

    /** One submission's transitions, oldest first. */
    @Transactional(readOnly = true)
    public List<SubmissionHistoryEntry> history(UUID submissionId) {
        return submissionService.historyForReview(submissionId).stream()
                .map(SubmissionHistoryEntry::from)
                .toList();
    }

    /** One submission's file, so the reviewer can read the work before deciding (FR-17). */
    @Transactional(readOnly = true)
    public DownloadableFile download(UUID submissionId) {
        return submissionService.downloadForReview(submissionId);
    }

    /**
     * Records a decision, after checking that it is a decision this application accepts.
     *
     * <p>The three rules, and why each is here rather than in the template:
     *
     * <ol>
     *   <li><strong>The outcome must be a decision.</strong> {@code SUBMITTED} is a status but not
     *       a decision, and a form field can carry it.</li>
     *   <li><strong>A rejection requires a reason</strong> (FR-19). The form marks the field
     *       required, which a crafted request ignores entirely — so the control is here. The
     *       {@code Submission} entity checks it a third time, which is what makes a reason-less
     *       rejection unrepresentable rather than merely refused.</li>
     *   <li><strong>An approval must not carry a reason.</strong> The column is called
     *       {@code rejection_reason}; text stored there against approved work would be rendered to
     *       the student under a heading that contradicts their own status. Refused rather than
     *       silently dropped, because a reviewer who typed a comment and chose Approve meant
     *       something, and quietly discarding it would lose it without telling them.</li>
     * </ol>
     *
     * <p>Nothing is written when any of these fails: this method throws before it calls the
     * service, so a refused decision leaves the submission and its history exactly as they were.
     *
     * @param reviewerId   the deciding reviewer, from the authenticated principal
     * @param submissionId the submission to decide
     * @param decision     the outcome and its note
     * @return the decided submission
     * @throws InvalidReviewException if the decision breaks one of the rules above
     * @throws com.vit.assignmentportal.submission.SubmissionAlreadyDecidedException if the
     *         submission has already been decided (FR-20)
     */
    @Transactional
    public Submission decide(UUID reviewerId, UUID submissionId, ReviewDecision decision) {
        if (decision == null || decision.outcome() == null) {
            throw new InvalidReviewException("Choose whether to approve or reject this submission.");
        }

        SubmissionStatus outcome = decision.outcome();
        if (outcome != SubmissionStatus.APPROVED && outcome != SubmissionStatus.REJECTED) {
            throw new InvalidReviewException(
                    "A decision must be either an approval or a rejection.");
        }

        String note = decision.note() == null || decision.note().isBlank()
                ? null
                : decision.note().trim();

        if (outcome == SubmissionStatus.REJECTED && note == null) {
            throw new InvalidReviewException("A rejection requires a reason.");
        }
        if (outcome == SubmissionStatus.APPROVED && note != null) {
            throw new InvalidReviewException(
                    "An approval cannot carry a rejection reason. Clear the reason, or reject "
                            + "the submission instead.");
        }
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            // Refused rather than truncated, for the same reason the submission feature refuses an
            // over-long title: truncation would store something the reviewer did not write, and
            // the student would read it as the whole reason.
            throw new InvalidReviewException(
                    "The reason may be at most " + MAX_NOTE_LENGTH + " characters.");
        }

        return submissionService.recordDecision(reviewerId, submissionId, outcome, note);
    }
}
