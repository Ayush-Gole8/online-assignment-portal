package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.common.PortalException;

/**
 * A reviewer tried to decide a submission that already has a terminal status.
 *
 * <p>A decision is final (FR-20): once a submission is {@code APPROVED} or {@code REJECTED} it
 * cannot be decided again, and the only non-terminal state is {@code SUBMITTED}. This is thrown by
 * the {@link Submission} entity itself — the transition methods are the one place the rule can be
 * enforced regardless of which caller, now or later, asks for the change. Keeping it on the entity
 * is what makes "the terminality rule is enforced in the service, not the view" true in the
 * strongest possible way: it holds even for a caller that never went through a controller.
 *
 * <p>It also covers the concurrent case's sequential sibling. Two reviewers who open the same
 * submission and both decide it produce two different failures: the one whose transaction commits
 * second with a stale row is stopped by the optimistic lock
 * ({@code ObjectOptimisticLockingFailureException}); a reviewer who decides a submission that was
 * <em>already</em> terminal when they loaded it is stopped by this. Both end as a message on the
 * page rather than a silent overwrite.
 *
 * <p>Carries a message written for a person and safe to render verbatim — it names no identifier,
 * no SQL, and no internal state ({@code CLAUDE.md} §7 rule 11), which is what {@link PortalException}
 * declares. The reviewer flow catches this and re-renders the page with the message rather than
 * surfacing a bare error page; if one ever escaped that flow, the web layer's handler answers
 * {@code 409 Conflict} — the decision is refused because of the state the resource is in.
 */
public class SubmissionAlreadyDecidedException extends PortalException {

    public SubmissionAlreadyDecidedException(String message) {
        super(message);
    }

    /** The message used by every call site. It describes the rule, not the row. */
    public static SubmissionAlreadyDecidedException forDecidedSubmission() {
        return new SubmissionAlreadyDecidedException(
                "This submission has already been decided, and a decision is final.");
    }
}
