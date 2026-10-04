package com.vit.assignmentportal.storage;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * What the portal will accept as an upload, bound from {@code app.upload.*}.
 *
 * <h2>Why this is here and not in {@code config}</h2>
 *
 * <p>The same reason {@link StorageProperties} gives: {@code config} is the outermost ring and a
 * capability may not depend on it. {@link FileValidationService} needs this type, so a copy in
 * {@code config} would create the edge {@code storage → config} that
 * docs/architecture/application-structure.md §4 rule 3 forbids. Binding is cross-cutting; the shape
 * of a capability's settings belongs to the capability.
 *
 * <p>Registered by {@code @ConfigurationPropertiesScan} on {@code AssignmentPortalApplication}.
 *
 * <h2>What was wrong with the constants this replaces</h2>
 *
 * <p>The size limit was a {@code public static final long} on {@link FileValidationService}, and
 * the accepted media type a {@code public static final String} beside it. Both were correct and
 * both were invisible: nothing in {@code application.yml} — the file an operator opens to learn
 * what this service accepts — mentioned either. Worse, the limit was stated a second time in
 * {@code spring.servlet.multipart.max-file-size}, as a literal in a different syntax, and a third
 * time as {@code ck_submissions_file_size} in {@code V2__submissions.sql}. Three copies of one
 * number in three languages, kept in agreement by a comment.
 *
 * <p>Now the limit has one home, and {@code application.yml} expresses the servlet limit by
 * <em>referring to the same environment variable</em> rather than restating the number, so the two
 * cannot drift. (The schema constraint is deliberately still independent: it is the last line of
 * defence and should be able to refuse a value that a misbound property let through.)
 *
 * @param maxFileSize       the largest artefact that may be stored. Bound from
 *                          {@code app.upload.max-file-size}, which
 *                          {@code application.yml} maps to {@code UPLOAD_MAX_FILE_SIZE} and
 *                          defaults to {@code 10MB}. Not a secret, so it is committed as a default
 *                          like every other non-credential setting (ADR-004, {@code CLAUDE.md} §8).
 * @param allowedContentType the media type the portal accepts, bound from
 *                          {@code app.upload.allowed-content-type}. See the constructor for why it
 *                          is validated rather than trusted.
 */
@ConfigurationProperties(prefix = "app.upload")
public record UploadProperties(DataSize maxFileSize, String allowedContentType) {

    /**
     * The one format the MVP accepts. Named here so the guard below and
     * {@link FileValidationService}'s extension and signature checks all read the same value.
     */
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    /**
     * Validates the binding once, at startup, rather than at the first upload.
     *
     * <p>A {@code @ConfigurationProperties} record that accepts anything is a record that moves every
     * failure to the moment a student is watching. Each check below refuses a configuration that
     * cannot work, so the application stops with a sentence naming the variable an operator has to
     * correct — which is the same reasoning {@link StorageService}'s constructor uses for a missing
     * storage root.
     *
     * <p>The media type is <strong>guarded, not free</strong>, and that is deliberate rather than
     * lazy. {@link FileValidationService} proves a file is a PDF by reading the {@code %PDF-} header
     * it begins with — a check that is meaningful only for the format it was written for. Setting
     * this property to {@code image/png} would not widen what the portal accepts; it would make
     * every upload fail the signature check with "That file is not a valid PDF.", which is a
     * confusing way to learn that a second format needs a second signature check and not a different
     * string. Refusing the value at startup says that once, clearly, to whoever changed it.
     *
     * @throws IllegalStateException if the size is absent or not positive, or the media type is
     *                               blank or not the one format the content check can prove
     */
    public UploadProperties {
        if (maxFileSize == null) {
            throw new IllegalStateException(
                    "app.upload.max-file-size is not configured. Set UPLOAD_MAX_FILE_SIZE, or "
                    + "remove the override to use the 10MB default in application.yml.");
        }
        if (maxFileSize.toBytes() < 1) {
            throw new IllegalStateException(
                    "app.upload.max-file-size must be at least one byte, but is " + maxFileSize
                    + ". A limit of zero refuses every upload, including a valid one.");
        }
        if (allowedContentType == null || allowedContentType.isBlank()) {
            throw new IllegalStateException(
                    "app.upload.allowed-content-type is not configured. Set "
                    + "UPLOAD_ALLOWED_CONTENT_TYPE, or remove the override to use the "
                    + PDF_CONTENT_TYPE + " default in application.yml.");
        }
        // Normalised so that every later comparison — including the one the failure message below
        // makes — is against one spelling. A media type is case-insensitive by definition.
        allowedContentType = allowedContentType.trim().toLowerCase(Locale.ROOT);
        if (!PDF_CONTENT_TYPE.equals(allowedContentType)) {
            throw new IllegalStateException(
                    "app.upload.allowed-content-type is '" + allowedContentType + "', but this "
                    + "application accepts only " + PDF_CONTENT_TYPE + ". The upload validator "
                    + "proves a file's format by checking that it begins with the %PDF- signature, "
                    + "which cannot be generalised by configuration. Accepting another format means "
                    + "adding a check for its signature in FileValidationService first.");
        }
    }

    /** The limit in bytes, which is the unit the validator and the storage service compare in. */
    public long maxFileSizeBytes() {
        return maxFileSize.toBytes();
    }

    /**
     * The sentence a student is shown when a file is over the limit.
     *
     * <p>Derived from the bound value rather than written out beside it. The literal this replaces —
     * "The file is larger than the 10 MB limit." — was stated twice, once in
     * {@link FileValidationService} and once in {@code SubmissionController}'s handler for the
     * container's own refusal, so lowering the limit would have left one of them lying about it.
     */
    public String oversizedMessage() {
        return "The file is larger than the " + describeLimit() + " limit.";
    }

    /**
     * The limit in the largest binary unit it divides evenly into.
     *
     * <p>Mebibytes, not megabytes: ten mebibytes is what {@code DataSize.ofMegabytes(10)} and the
     * container's {@code 10MB} both mean, and a decimal rendering would be a different number. The
     * smaller units exist so that a limit below 1 MB does not describe itself as "0 MB".
     */
    private String describeLimit() {
        long bytes = maxFileSize.toBytes();
        if (bytes % (1024 * 1024) == 0) {
            return (bytes / (1024 * 1024)) + " MB";
        }
        if (bytes % 1024 == 0) {
            return (bytes / 1024) + " KB";
        }
        return bytes + " bytes";
    }
}
