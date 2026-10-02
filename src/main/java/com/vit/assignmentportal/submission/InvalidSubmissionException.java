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

    /**
     * The form field the refusal concerns — {@code title}, {@code courseCode} or {@code file} —
     * or {@code null} when the refusal is not about one field.
     *
     * <p>This exists so the upload page can mark the offending input rather than only showing a
     * message above the form. It is a <strong>presentation hint</strong>, not a control: the message
     * is what refused the upload and the message is unchanged whether or not a field is named. A
     * template that ignored this value entirely would still refuse the same uploads for the same
     * reasons; it would only point at them less precisely.
     *
     * <p>It is the form field's name, not a column name or a path — the two are deliberately kept
     * identical in the template ({@code name="title"} on the input), so the page can use the value
     * directly without a lookup table that could drift.
     */
    private final String field;

    /** A refusal that is not about one field in particular. */
    public InvalidSubmissionException(String message) {
        this(message, null);
    }

    /**
     * A refusal about one field.
     *
     * @param message the student-facing sentence, safe to render verbatim
     * @param field   the form field the sentence applies to, or {@code null}
     */
    public InvalidSubmissionException(String message, String field) {
        super(message);
        this.field = field;
    }

    /** The form field this refusal applies to, or {@code null} when it applies to none. */
    public String field() {
        return field;
    }
}
