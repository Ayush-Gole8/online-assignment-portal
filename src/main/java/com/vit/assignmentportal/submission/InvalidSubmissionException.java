package com.vit.assignmentportal.submission;

/**
 * The student's upload was refused before anything was stored.
 *
 * <p>Carries a message written for the student, and the contract of this type is that the message
 * is <strong>safe to render verbatim</strong>: it is composed by this application, it never
 * includes text supplied by the client, and it never describes a filesystem path, a SQL
 * statement, or a configuration value ({@code CLAUDE.md} §7 rule 11). Every message the class is
 * thrown with is a complete, actionable sentence.
 *
 * <p>Named for what happened — the submission was not valid — rather than
 * {@code SubmissionRejectedException}, which would collide with {@link
 * com.vit.assignmentportal.common.SubmissionStatus#REJECTED}. A refused upload and a reviewer's
 * rejection are different events: one means nothing was stored, the other is a decision recorded
 * against a stored artefact.
 *
 * <p>An exception rather than a return value because the alternative — a result object every
 * caller must remember to inspect — makes it possible to ignore a refusal and store the file
 * anyway. A service that guarantees "nothing is stored when validation fails" should not offer a
 * code path that quietly breaks that guarantee.
 */
public class InvalidSubmissionException extends RuntimeException {

    public InvalidSubmissionException(String message) {
        super(message);
    }
}
