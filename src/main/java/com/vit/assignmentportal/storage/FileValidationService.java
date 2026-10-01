package com.vit.assignmentportal.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Decides whether an uploaded file may be stored at all: PDF only, at most 10 MB, and verified
 * by its content rather than by what it claims to be ({@code CLAUDE.md} §7 rule 4).
 *
 * <p>The five checks are ordered by cost and by how much the answer is worth, and the order is
 * load-bearing:
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

    /**
     * The maximum accepted size, matching {@code spring.servlet.multipart.max-file-size} in
     * {@code application.yml} and the {@code ck_submissions_file_size} constraint in
     * {@code V2__submissions.sql}.
     *
     * <p>Ten mebibytes, not ten million bytes. The limit is stated in the requirements as
     * "10 MB", and the container-level limit is expressed in the same binary unit by Spring Boot;
     * a decimal interpretation here would make the two disagree by roughly 4%, which is precisely
     * the kind of gap that produces a bug nobody can reproduce.
     */
    public static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** The single accepted media type in the MVP (docs/requirements/mvp.md). */
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    private static final String PDF_EXTENSION = ".pdf";

    /**
     * The five bytes every PDF begins with, per the file header defined in PDF 1.7 §7.5.2.
     *
     * <p>Checked rather than the shorter {@code %PDF} because the hyphen is part of the
     * specification and its absence is a reliable sign of a file that was not produced by a PDF
     * writer.
     */
    private static final byte[] PDF_MAGIC_BYTES = {'%', 'P', 'D', 'F', '-'};

    /**
     * The width of the {@code original_file_name} column. A name longer than this cannot be
     * stored, so it is truncated on the way in rather than rejected — see
     * {@link #safeDisplayName(String)}.
     */
    private static final int MAX_FILE_NAME_LENGTH = 255;

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

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            return Optional.of("The file is larger than the 10 MB limit.");
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.isBlank()) {
            return Optional.of("The uploaded file has no name.");
        }

        if (!fileName.toLowerCase(Locale.ROOT).endsWith(PDF_EXTENSION)) {
            return Optional.of("Only PDF files are accepted.");
        }

        String declaredType = file.getContentType();
        if (declaredType != null && !declaredType.isBlank()
                && !PDF_CONTENT_TYPE.equalsIgnoreCase(declaredType.trim())) {
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
