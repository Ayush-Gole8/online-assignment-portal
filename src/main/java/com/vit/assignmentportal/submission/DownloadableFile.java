package com.vit.assignmentportal.submission;

/**
 * A stored artefact on its way to a browser: the bytes, and the two values the response needs to
 * describe them honestly.
 *
 * <p>It exists so that a controller can serve a download without seeing a {@link Submission} or a
 * {@code StorageIdentifier}. The entity carries the storage identifier, and a handler that holds
 * the entity is one line away from putting that identifier in a header or a log; this record
 * carries the file name a <em>person</em> knows instead.
 *
 * <p><strong>{@link #contentType} is the validated type recorded at upload, not a guess and not
 * anything the client said.</strong> It is served as a fixed value with
 * {@code Content-Disposition: attachment}, never inline ({@code CLAUDE.md} §7 rule 10): a PDF
 * rendered inline is a document executing in the origin of this application, and an attachment is
 * not.
 *
 * <p>The bytes are held in memory. That is acceptable precisely because the upload limit is 10 MB
 * and enforced in three places (the container, {@code FileValidationService}, and
 * {@code ck_submissions_file_size}); it is the same decision the upload path already makes when it
 * reads the part into a {@code byte[]} to checksum it. A streaming download would be the right
 * change if that limit ever rose.
 *
 * @param fileName    the name to offer the browser — {@code original_file_name}, already normalised
 *                    on the way in by {@code FileValidationService.safeDisplayName}
 * @param contentType the media type validated at upload time
 * @param content     the bytes read back from the volume
 */
public record DownloadableFile(String fileName, String contentType, byte[] content) {
}
