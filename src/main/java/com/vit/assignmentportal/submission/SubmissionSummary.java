package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.common.SubmissionStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the student's submission list.
 *
 * <p>A record rather than the entity because the view layer must not see {@link Submission}
 * (docs/architecture/application-structure.md §6). The reason is not tidiness: an entity carries
 * a {@code studentId} and a {@code storedFileName}, and a template that can reach a field can
 * render it — which is how an internal identifier ends up in a page the owner is not supposed to
 * see it in. A read model exposes exactly what is being shown and nothing else.
 *
 * <p>It is also a boundary against the persistence context. The entity is attached to a session
 * ({@code open-in-view} is off); handing it to a template would encourage lazy access outside a
 * transaction. A record holds values that were read inside one.
 *
 * <p>Deliberately smaller than {@link SubmissionDetail}: the list does not show the checksum or
 * the file size, so it does not carry them.
 *
 * @param id               the submission's identifier, used to build the detail link
 * @param title            the student's title for the work
 * @param courseCode       the course the work belongs to
 * @param originalFileName the file name as the student knew it
 * @param status           the current lifecycle state
 * @param createdAt        when the work was handed in
 */
public record SubmissionSummary(
        UUID id,
        String title,
        String courseCode,
        String originalFileName,
        SubmissionStatus status,
        Instant createdAt
) {

    /** Projects a stored submission onto the fields the list displays. */
    public static SubmissionSummary from(Submission submission) {
        return new SubmissionSummary(
                submission.getId(),
                submission.getTitle(),
                submission.getCourseCode(),
                submission.getOriginalFileName(),
                submission.getStatus(),
                submission.getCreatedAt());
    }

    /**
     * {@link #createdAt}, formatted for display.
     *
     * <p>A derived value rather than a component: it is the same fact as {@code createdAt}, and
     * storing it separately would allow the two to disagree.
     */
    public String submittedOn() {
        return SubmissionTimestampFormat.format(createdAt);
    }
}
