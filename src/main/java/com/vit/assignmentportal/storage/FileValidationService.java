package com.vit.assignmentportal.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Decides whether an uploaded file may be stored at all: PDF only, at most the configured size, and
 * verified by its content rather than by what it claims to be ({@code CLAUDE.md} §7 rule 4).
 *
 * <h2>What it decides, and what it is told</h2>
 *
 * <p>The two numbers it used to own — the size limit and the accepted media type — are now
 * {@link UploadProperties}, bound from the environment and validated at startup. That split is the
 * point: this class owns the <em>checks</em>, which are logic and belong in code, while the limits
 * those checks apply are <em>settings</em>, which belong in configuration. A validator that also
 * decided what the limits were could not be tested at a size other than ten mebibytes, and could not
 * be told a limit by an environment.
 *
 * <h2>The five checks are ordered, and the order is load-bearing</h2>
 *
 * <ol>
 *   <li><strong>Empty.</strong> Cheapest possible rejection.</li>
 *   <li><strong>Size</strong>, before a single byte is read into memory. A limit enforced after
 *       the file has been buffered is a limit that has already been paid for.</li>
 *   <li><strong>Extension.</strong> Cheap, and it produces the clearest message for the common
 *       mistake — someone picking a {@code .docx} by accident.</li>
 *   <li><strong>Declared content type.</strong> Advisory only, and treated as such: this value
 *       comes from the client and an attacker sets it freely. It is checked because a browser
 *       that reports {@code text/html} for a {@code .pdf} is a signal worth refusing, not
 *       because it proves anything. A missing value is not a failure — some clients omit it.</li>
 *   <li><strong>The leading bytes.</strong> The only check that is actually authoritative. A
 *       file named {@code essay.pdf}, reported as {@code application/pdf}, whose content does
 *       not begin with {@code %PDF-} is not a PDF, and none of the checks above would have
 *       caught it.</li>
 * </ol>
 *
 * <p>The result is a message rather than an exception so that the caller can decide what a
 * refusal means — re-render a form, in this application's case. The messages are written for a
 * student and contain no client-supplied text, which is what makes them safe to render verbatim.
 */
@Service
public class FileValidationService {

    private static final String PDF_EXTENSION = ".pdf";

    /**
     * The five bytes every PDF begins with, per the file header defined in PDF 1.7 §7.5.2.
     *
     * <p>Checked rather than the shorter {@code %PDF} because the hyphen is part of the
     * specification and its absence is a reliable sign of a file that was not produced by a PDF
     * writer.
     *
     * <p>The one piece of format knowledge this class cannot be configured out of, and the reason
     * {@link UploadProperties} refuses a media type other than {@code application/pdf} rather than
     * letting a second format be named and then fail here.
     */
    private static final byte[] PDF_MAGIC_BYTES = {'%', 'P', 'D', 'F', '-'};

    /**
     * The width of the {@code original_file_name} column. A name longer than this cannot be
     * stored, so it is truncated on the way in rather than rejected — see
     * {@link #safeDisplayName(String)}.
     *
     * <p>Stated here rather than read from {@code submission.Submission}, because {@code storage} is
     * a capability and may depend only on {@code common} — it cannot import a feature's entity
     * (docs/architecture/application-structure.md §4 rule 2). The coupling is real and is why
     * {@code V2__submissions.sql} and this constant are worth keeping in view together.
     */
    private static final int MAX_FILE_NAME_LENGTH = 255;

    private final UploadProperties uploadProperties;

    public FileValidationService(UploadProperties uploadProperties) {
        this.uploadProperties = uploadProperties;
    }

    /**
     * Reasons to refuse the upload, or empty when it is acceptable.
     *
     * @param file the uploaded part; {@code null} is treated as "nothing was chosen"
     * @return the message to show the student, or {@link Optional#empty()} if the file may be
     *         stored
     */
    public Optional<String> validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return Optional.of("Choose a PDF to upload.");
        }

        if (file.getSize() > uploadProperties.maxFileSizeBytes()) {
            return Optional.of(oversizedFileMessage());
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            return Optional.of("The uploaded file has no name.");
        }

        if (!fileName.toLowerCase(Locale.ROOT).endsWith(PDF_EXTENSION)) {
            return Optional.of("Only PDF files are accepted.");
        }

        String declaredType = file.getContentType();
        String acceptedType = acceptedContentType();
        if (declaredType != null && !declaredType.isBlank()
                && !acceptedType.equalsIgnoreCase(declaredType.trim())) {
            // Deliberately the same sentence as the extension failure. The client was told only
            // that the file is not acceptable; echoing the media type it claimed would describe
            // the check to someone probing it, and tells a genuine user nothing they can act on.
            return Optional.of("Only PDF files are accepted.");
        }

        if (!beginsWithPdfMagicBytes(file)) {
            return Optional.of("That file is not a valid PDF.");
        }

        return Optional.empty();
    }

    /**
     * The media type the portal accepts, for the caller that records it against a stored file.
     *
     * <p>{@link #validate(MultipartFile)} accepts a missing declared type deliberately, but
     * {@code submissions.content_type} is {@code NOT NULL} and describes the type that was
     * <em>validated</em> rather than the string the client claimed. This is that value, so the
     * entity, the column, the migration and the validator all name one thing.
     */
    public String acceptedContentType() {
        return uploadProperties.allowedContentType();
    }

    /**
     * The sentence shown when a file is over the size limit.
     *
     * <p>Public because a second code path refuses an oversized upload: the container's
     * {@code spring.servlet.multipart.max-file-size} rejects the request before this class ever sees
     * it, and {@code SubmissionController} has to produce a message for that case too. Exposing the
     * sentence here means both paths say the same thing about the same limit instead of each
     * carrying its own copy of the number.
     */
    public String oversizedFileMessage() {
        return uploadProperties.oversizedMessage();
    }

    /**
     * The file's leading bytes, compared against the PDF header.
     *
     * <p>Only the first five bytes are read. Streaming the whole part to look for a signature
     * would undo the point of checking the size first, and a PDF's signature is at the start by
     * definition.
     */
    private boolean beginsWithPdfMagicBytes(MultipartFile file) {
        byte[] header = new byte[PDF_MAGIC_BYTES.length];
        try (InputStream content = file.getInputStream()) {
            if (content.readNBytes(header, 0, header.length) < header.length) {
                // Shorter than the signature, so it cannot carry one.
                return false;
            }
        } catch (IOException cause) {
            // Bytes that cannot be read are not bytes that can be stored. Reported as an invalid
            // PDF rather than as an I/O error: the student cannot act on the distinction, and the
            // storage failure would surface later anyway.
            return false;
        }
        return Arrays.equals(header, PDF_MAGIC_BYTES);
    }

    /**
     * The file name to record for display, stripped of everything that makes a name unsafe.
     *
     * <p><strong>This name is never used as a path.</strong> The file on disk is located by its
     * {@link StorageIdentifier}, which is generated server-side and cannot contain a separator,
     * so path traversal is already impossible ({@code CLAUDE.md} §7 rule 5). What remains is the
     * text itself: it is stored in a column, shown to a reviewer, and may reach a log line. That
     * is what this method makes safe.
     *
     * <p>Normalised, not rejected. A browser that sends a full client-side path — older versions
     * of some browsers did — is not attacking anything, and refusing a perfectly good upload
     * because of it would be user-hostile for no security gain. So the directory part is
     * discarded and the base name is kept.
     *
     * @param originalFileName the name as the client supplied it; must be non-blank
     * @throws IllegalArgumentException if the name is blank, or is nothing but directory
     *                                  components; this is unreachable through
     *                                  {@link #validate(MultipartFile)}, which rejects those
     *                                  uploads first
     */
    public String safeDisplayName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new IllegalArgumentException("The uploaded file has no name");
        }

        String name = originalFileName;
        int lastSeparator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            name = name.substring(lastSeparator + 1);
        }

        // Control characters would otherwise reach a log line, where they can forge entries, and
        // a response header, where they can split it.
        name = name.replaceAll("\\p{Cntrl}", "").trim();

        if (name.isEmpty()) {
            throw new IllegalArgumentException("The uploaded file name has no usable characters");
        }

        // Truncate from the front so the extension survives: `.pdf` is the part a person reads.
        // The name is display metadata, so losing its leading characters costs nothing.
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            name = name.substring(name.length() - MAX_FILE_NAME_LENGTH);
        }

        return name;
    }
}
