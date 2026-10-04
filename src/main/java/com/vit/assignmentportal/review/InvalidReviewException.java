package com.vit.assignmentportal.review;

import com.vit.assignmentportal.common.PortalException;

/**
 * The reviewer's decision was not acceptable, and nothing was recorded.
 *
 * <p>Carries a message written for a reviewer, and the contract of this type is the same as
 * {@code InvalidSubmissionException}'s: the message is <strong>safe to render verbatim</strong>. It
 * is composed by this application, it never includes text the client supplied, and it never
 * describes a filesystem path, a SQL statement, or a configuration value
 * ({@code CLAUDE.md} §7 rule 11). Inheriting {@link PortalException} is how that contract is
 * declared rather than merely asserted in prose.
 *
 * <p>This is the <em>policy</em> failure — a rejection with no reason, an approval that carried
 * one, an outcome that is not a decision. It is distinct from
 * {@code SubmissionAlreadyDecidedException}, which is the <em>state</em> failure: the decision was
 * well-formed but the submission was no longer open to it. The two are separate types because a
 * reviewer can fix the first by editing the form and cannot fix the second at all.
 *
 * <p>An exception rather than a return value, for the same reason the submission feature chose one:
 * a result object every caller must remember to inspect makes it possible to ignore a refusal and
 * record the decision anyway.
 */
public class InvalidReviewException extends PortalException {

    public InvalidReviewException(String message) {
        super(message);
    }
}
