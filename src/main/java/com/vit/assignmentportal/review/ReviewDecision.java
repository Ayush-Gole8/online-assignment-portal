package com.vit.assignmentportal.review;

import com.vit.assignmentportal.common.SubmissionStatus;

/**
 * What a reviewer decided, as submitted by the decision form.
 *
 * <p>A record named for its role rather than {@code ReviewDto} ({@code CLAUDE.md} §5). It is the
 * one argument {@link ReviewService#decide} takes, which keeps the outcome and its note together:
 * the rules relating the two — a rejection needs a note, an approval must not have one — are about
 * the pair, and a signature that took two loose parameters would invite a caller to validate one
 * without the other.
 *
 * <p><strong>The outcome is not trusted.</strong> It arrives from a form field, so
 * {@link ReviewService} checks it is one of the two real decisions before anything acts on it.
 * Binding it as a {@link SubmissionStatus} means a value that is not a status at all is refused by
 * the framework's conversion before a handler runs; a value that <em>is</em> a status but not a
 * decision — {@code SUBMITTED} — is refused by the service.
 *
 * @param outcome the decision: {@code APPROVED} or {@code REJECTED}
 * @param note    the reviewer's comment. Required when rejecting (FR-19); must be absent when
 *                approving, because a reason stored against approved work contradicts it
 */
public record ReviewDecision(SubmissionStatus outcome, String note) {
}
