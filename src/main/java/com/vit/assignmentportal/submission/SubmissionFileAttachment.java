package com.vit.assignmentportal.submission;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Builds the HTTP response that serves a stored artefact.
 *
 * <p>Both download routes — the student's own, and the reviewer's — must answer identically, and
 * the way they must answer is a security rule rather than a formatting preference
 * ({@code CLAUDE.md} §7 rule 10):
 *
 * <ul>
 *   <li><strong>{@code Content-Disposition: attachment}</strong>, never {@code inline}. A PDF
 *       rendered inline is a document executing inside this application's origin, with access to
 *       the session cookie's context; an attachment is a file the browser hands to the operating
 *       system. This is the single most important header here.</li>
 *   <li><strong>A fixed content type</strong> — the one validated at upload, not one sniffed from
 *       the bytes and not one the client ever supplied.</li>
 *   <li><strong>{@code X-Content-Type-Options: nosniff}</strong>, so a browser cannot decide the
 *       bytes are really HTML and render them as such. Without it the fixed type above is a
 *       suggestion.</li>
 * </ul>
 *
 * <p>The file name is set through {@link ContentDisposition}, which quotes and encodes it properly.
 * It is {@code original_file_name}, which {@code FileValidationService.safeDisplayName} already
 * stripped of directory components and control characters on the way in — a header value is exactly
 * where an un-normalised name would let a newline split the response.
 *
 * <p>A class with one static method rather than a duplicated block in two controllers. It is named
 * for what it produces; it is emphatically not a {@code ResponseUtils}
 * ({@code CLAUDE.md} §5).
 */
public final class SubmissionFileAttachment {

    /**
     * {@code X-Content-Type-Options}. Spelled out here rather than taken from a constant because
     * the obvious homes are both wrong for this class: {@code HttpHeaders} does not define it, and
     * the header's own writer lives in {@code spring-security-web}, which no feature package
     * imports. It is a fixed header name from a fixed specification, not a value that can drift.
     */
    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private SubmissionFileAttachment() {
    }

    /**
     * The response for one downloaded artefact.
     *
     * @param file the bytes and the two values needed to describe them
     * @return a {@code 200 OK} carrying the file as an attachment
     */
    public static ResponseEntity<byte[]> of(DownloadableFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(file.fileName())
                                .build()
                                .toString())
                .header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.content().length)
                .body(file.content());
    }
}
