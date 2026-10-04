package com.vit.assignmentportal.support;

import com.vit.assignmentportal.submission.Submission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The bytes and rows the submission tests work with, stated once.
 *
 * <h2>Why the PDF is a constant</h2>
 *
 * <p>A great many tests need "a valid upload", and before this class each one wrote its own
 * {@code %PDF-1.7} header. Two tests that disagreed about what a valid PDF looked like would be
 * testing two different inputs while both claiming to test "a valid PDF" — and the disagreement
 * would only surface as one of them failing for a reason that looked like a bug in the code.
 *
 * <p>{@link #PDF_BYTES} begins with the {@code %PDF-} signature {@code FileValidationService} checks,
 * so it passes validation; it is short enough to read in an assertion failure, and it is
 * deliberately not a real document — nothing under test parses it.
 *
 * <h2>The checksum is computed, not asserted</h2>
 *
 * <p>{@link #sha256Hex} recomputes the digest of a byte array independently, with the JDK's own
 * {@link MessageDigest} rather than with the application's {@link com.vit.assignmentportal.storage
 * .ChecksumService}. An integration test asserting "the stored checksum is the digest of the stored
 * bytes" then compares the application's answer against a second, separate implementation of the
 * same standard — which is the only way that assertion can fail. Comparing the application's output
 * against itself would pass for any deterministic function.
 *
 * <h2>The row builder earns its place</h2>
 *
 * <p>{@link Submission#upload} takes nine arguments, and the tests that need a stored row rarely care
 * about all nine. {@link #draft} supplies the ones that are not the point — a title, a course code, a
 * file name, a fresh storage identifier — so a test says only what it is actually arranging. The
 * values are all valid for the constraints the schema enforces, so a test that fails does so for its
 * own reason rather than because a fixture default was rejected.
 */
public final class SubmissionTestData {

    /** The one media type the portal accepts, matching {@code application.yml}'s default. */
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    /** The five bytes every PDF begins with, then an end-of-file marker. */
    public static final byte[] PDF_BYTES = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    /** A title that satisfies {@code submissions.title}'s width. */
    public static final String DEFAULT_TITLE = "Coursework submission";

    /** A course code that satisfies {@code submissions.course_code}'s width. */
    public static final String DEFAULT_COURSE_CODE = "CS101";

    /** The file name a valid upload carries, which validation requires to end in {@code .pdf}. */
    public static final String DEFAULT_FILE_NAME = "essay.pdf";

    private SubmissionTestData() {
    }

    /**
     * A valid upload, named {@link #DEFAULT_FILE_NAME} and declaring the PDF content type.
     *
     * <p>The {@code file} field name matches the upload form's input, which is what a controller
     * binds the part from.
     */
    public static MockMultipartFile pdf() {
        return pdf(DEFAULT_FILE_NAME);
    }

    /** A valid upload under a chosen file name, for the filename-handling cases. */
    public static MockMultipartFile pdf(String originalFileName) {
        return new MockMultipartFile("file", originalFileName, PDF_CONTENT_TYPE, PDF_BYTES);
    }

    /**
     * An upload that is rejected by {@code FileValidationService}, for the refusal cases.
     *
     * <p>It carries the PDF's own bytes under a name that is not a PDF, so the refusal it produces is
     * the extension check's — the cheapest and clearest of the five — rather than a signature
     * failure that would only occur later.
     */
    public static MockMultipartFile notAPdf() {
        return new MockMultipartFile("file", "essay.docx", PDF_CONTENT_TYPE, PDF_BYTES);
    }

    /**
     * A submission row describing {@link #PDF_BYTES}, owned by {@code studentId} and created at
     * {@code createdAt}.
     *
     * <p>The storage identifier is a fresh random UUID, which is the canonical form
     * {@code stored_file_name} is constrained to hold.
     */
    public static Submission draft(UUID studentId, Instant createdAt) {
        return Submission.upload(
                studentId,
                DEFAULT_TITLE,
                DEFAULT_COURSE_CODE,
                DEFAULT_FILE_NAME,
                UUID.randomUUID().toString(),
                PDF_CONTENT_TYPE,
                PDF_BYTES.length,
                sha256Hex(PDF_BYTES),
                createdAt);
    }

    /**
     * The SHA-256 digest of {@code content} as 64 lowercase hexadecimal characters.
     *
     * <p>An independent implementation of the standard, deliberately not the application's — see the
     * class note. It computes the digest of whatever it is handed, so a test can compare it against
     * the checksum recorded for a file rather than against a second call to the same method.
     */
    public static String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException cause) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", cause);
        }
    }
}
