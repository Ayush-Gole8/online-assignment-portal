package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.common.SubmissionStatus;
import java.time.Instant;

/**
 * One line of the status history as a page renders it.
 *
 * <p>A read model rather than the entity, for the reasons set out in {@link SubmissionSummary}: a
 * template that can reach a field can render it, and {@link SubmissionStatusHistory} carries an
 * {@code actorId}.
 *
 * <p><strong>The actor is deliberately absent.</strong> The history shown to a student says what
 * happened and when, not who did it. Naming the reviewer would disclose an identity the student has
 * no need for, and the audit row still records it for an administrator who does (FR-22). This is
 * the read model for a page, not for the audit trail.
 *
 * @param fromStatus the status before the transition, or {@code null} for the creation entry
 * @param toStatus   the status after the transition
 * @param note       the reviewer's comment, or {@code null} when the transition carried none
 * @param createdAt  when the transition happened
 */
public record SubmissionHistoryEntry(
        SubmissionStatus fromStatus,
        SubmissionStatus toStatus,
        String note,
        Instant createdAt
) {

    /** Projects a stored history row onto the fields a page displays. */
    public static SubmissionHistoryEntry from(SubmissionStatusHistory entry) {
        return new SubmissionHistoryEntry(
                entry.getFromStatus(),
                entry.getToStatus(),
                entry.getNote(),
                entry.getCreatedAt());
    }

    /**
     * Whether this entry is the submission's creation rather than a decision.
     *
     * <p>Derived from {@link #fromStatus} being absent, so the template does not have to express
     * "the first one" — which would be a position, and positions change when a row is added.
     */
    public boolean isCreation() {
        return fromStatus == null;
    }

    /** {@link #createdAt}, formatted for display. */
    public String occurredOn() {
        return SubmissionTimestampFormat.format(createdAt);
    }
}
