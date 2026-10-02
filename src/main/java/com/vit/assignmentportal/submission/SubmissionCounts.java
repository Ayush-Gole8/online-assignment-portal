package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.common.SubmissionStatus;

/**
 * How many of a student's submissions are in each state — the three numbers on the dashboard.
 *
 * <p>A record rather than three model attributes, so the page receives one value with a name rather
 * than three loose longs whose meaning is only in the controller that added them.
 *
 * <p>The three components are the three {@link SubmissionStatus} values, named in lower case as the
 * record accessors the template calls. That is the whole vocabulary — there is no fourth state, and
 * {@link #total()} is derived rather than carried so it cannot disagree with the parts it is made
 * of. A fourth status added to the enum would fail to compile here until somebody decided how the
 * dashboard should count it, which is the point.
 *
 * @param submitted how many are awaiting a decision
 * @param approved  how many have been approved
 * @param rejected  how many have been rejected
 */
public record SubmissionCounts(long submitted, long approved, long rejected) {

    /** All of the student's submissions — the sum of the three states, which are exhaustive. */
    public long total() {
        return submitted + approved + rejected;
    }
}
