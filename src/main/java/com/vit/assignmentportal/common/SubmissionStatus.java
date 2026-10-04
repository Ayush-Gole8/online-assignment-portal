package com.vit.assignmentportal.common;

/**
 * The lifecycle state of a submission.
 *
 * <p>Part of the shared kernel because more than one feature needs the vocabulary: the student's
 * listing renders it, and the reviewer's decision changes it. It deliberately carries no Spring
 * or persistence types, so it can be unit-tested and reasoned about on its own.
 *
 * <p>Stored as a <em>string</em>, never as an ordinal. A readable value survives a schema dump
 * and cannot silently change meaning if the constants are reordered — inserting a status in the
 * middle of an ordinal-encoded enum would silently reinterpret every existing row
 * (docs/architecture/data-model.md §3).
 *
 * <p>The reachable transitions are exactly {@code SUBMITTED -> APPROVED} and
 * {@code SUBMITTED -> REJECTED}. {@link #SUBMITTED} is the only non-terminal value, and the
 * {@code Submission} entity has no method that moves a row out of a terminal one — a decision is
 * final (FR-20).
 *
 * <p>A rejected submission is {@link #REJECTED}, not deleted. Rejection is a terminal state with
 * a recorded reason, which is what the student's status history reads from
 * (docs/requirements/mvp.md §3).
 */
public enum SubmissionStatus {

    /** Uploaded by a student and awaiting a reviewer's decision. The only non-terminal status. */
    SUBMITTED,

    /** Accepted by a reviewer. Terminal. */
    APPROVED,

    /** Refused by a reviewer, with a reason. Terminal. */
    REJECTED;

    /**
     * Whether this status is a reviewer's decision — one of the two terminal outcomes.
     *
     * <p>The distinction matters because {@link #SUBMITTED} is a status but not a decision, and a
     * form field can carry it. Both the reviewer's policy ({@code review.ReviewService}) and the
     * transition mechanism ({@code submission.SubmissionService}) have to answer this question, and
     * a literal {@code status != APPROVED && status != REJECTED} written in both is the same rule
     * stated twice. Stating it once, on the vocabulary it is about, is what keeps the two from
     * drifting — and it puts the rule where a fourth status added later must come and read it.
     */
    public boolean isDecision() {
        return this == APPROVED || this == REJECTED;
    }
}
