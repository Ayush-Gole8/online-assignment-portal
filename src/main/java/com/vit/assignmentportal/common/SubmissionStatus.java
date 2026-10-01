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
 * <p><strong>Only {@link #SUBMITTED} is reachable in this milestone.</strong> There is no
 * transition method and no code path that produces the other two; they exist so that the
 * database {@code CHECK} constraint and the column's vocabulary are fixed once, rather than
 * widened by a migration when the review feature lands. {@code SubmissionService} can only
 * create a {@code SUBMITTED} submission.
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
    REJECTED
}
