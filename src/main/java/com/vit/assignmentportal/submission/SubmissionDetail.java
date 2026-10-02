package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.common.SubmissionStatus;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Everything the detail page shows about one submission.
 *
 * <p>A read model, not the entity: see {@link SubmissionSummary} for why the entity never reaches
 * a template.
 *
 * @param id               the submission's identifier
 * @param title            the student's title for the work
 * @param courseCode       the course the work belongs to
 * @param originalFileName the file name as the student knew it
 * @param contentType      the validated media type, recorded at upload time
 * @param fileSizeBytes    the size of the stored artefact
 * @param checksumSha256   the digest of the stored bytes
 * @param status           the current lifecycle state
 * @param createdAt        when the work was handed in
 * @param updatedAt        when the row last changed
 * @param reviewedAt       when a reviewer decided, or {@code null} while none has
 * @param rejectionReason  why a reviewer refused the work, or {@code null} when it was approved or
 *                         is still undecided
 */
public record SubmissionDetail(
        UUID id,
        String title,
        String courseCode,
        String originalFileName,
        String contentType,
        long fileSizeBytes,
        String checksumSha256,
        SubmissionStatus status,
        Instant createdAt,
        Instant updatedAt,
        Instant reviewedAt,
        String rejectionReason
) {

    /** Projects a stored submission onto the fields the detail page displays. */
    public static SubmissionDetail from(Submission submission) {
        return new SubmissionDetail(
                submission.getId(),
                submission.getTitle(),
                submission.getCourseCode(),
                submission.getOriginalFileName(),
                submission.getContentType(),
                submission.getFileSizeBytes(),
                submission.getChecksumSha256(),
                submission.getStatus(),
                submission.getCreatedAt(),
                submission.getUpdatedAt(),
                submission.getReviewedAt(),
                submission.getRejectionReason());
    }

    /**
     * The file size in a unit a person reads.
     *
     * <p>This is a view concern, which is why it lives on the view model rather than in a
     * formatter bean or — worse — a static method on the entity. Translating a number of bytes
     * into "1.5 MB" is part of presenting this page, and nothing else needs it.
     *
     * <p>{@link Locale#ROOT} rather than the request's locale: a decimal separator that changes
     * with the caller's language would make the figure ambiguous in a log or a screenshot, and
     * this application is English-only.
     *
     * @return the size, never smaller than "1 KB" for a non-empty file — a stored PDF of 300
     *         bytes reporting "0 KB" reads as a bug
     */
    public String readableSize() {
        long kilobytes = Math.max(1, Math.round(fileSizeBytes / 1024.0));
        return kilobytes < 1024
                ? kilobytes + " KB"
                : "%.1f MB".formatted(Locale.ROOT, kilobytes / 1024.0);
    }

    /** {@link #createdAt}, formatted for display. */
    public String submittedOn() {
        return SubmissionTimestampFormat.format(createdAt);
    }

    /** {@link #updatedAt}, formatted for display. */
    public String lastChangedOn() {
        return SubmissionTimestampFormat.format(updatedAt);
    }

    /**
     * {@link #reviewedAt}, formatted for display.
     *
     * <p>Empty while no reviewer has decided, which the template renders as "Not yet reviewed"
     * rather than as a blank cell.
     */
    public String reviewedOn() {
        return SubmissionTimestampFormat.format(reviewedAt);
    }
}
