package com.vit.assignmentportal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vit.assignmentportal.support.TestCategories;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;

/**
 * The upload rules, in isolation.
 *
 * <p>No Spring context. Every branch here is a pure decision about bytes and names, which is
 * exactly what a unit test is for — a context would add a second of startup per test and prove
 * nothing about the rules ({@code docs/testing/strategy.md} §3.1).
 *
 * <p>The cases that matter most are the ones where the three checks disagree with each other: a
 * file named {@code .pdf} whose content is not a PDF, and a PDF whose declared media type is
 * wrong. Those are the shapes an attacker actually sends, and each is asserted separately so a
 * future refactor cannot quietly drop one of the checks and still pass.
 */
@Tag(TestCategories.UNIT)
class FileValidationServiceTest {

    private static final String PDF = UploadProperties.PDF_CONTENT_TYPE;

    /** The limits as {@code application.yml} commits them. */
    private static final UploadProperties UPLOAD_PROPERTIES =
            new UploadProperties(DataSize.ofMegabytes(10), PDF);

    /**
     * A deliberately different limit, used to prove the limit is a setting rather than a constant.
     *
     * <p>Before the limits were bound, this could not be written: the validator read a
     * {@code private static final} of its own, so every test necessarily exercised ten mebibytes and
     * nothing else.
     */
    private static final UploadProperties ONE_MEBIBYTE_LIMIT =
            new UploadProperties(DataSize.ofMegabytes(1), PDF);

    /** A file whose bytes begin with the PDF signature and nothing else in particular. */
    private static final byte[] PDF_BYTES = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    /** Bytes that are perfectly valid — for something that is not a PDF. */
    private static final byte[] NOT_A_PDF = "This is a text file wearing a PDF extension."
            .getBytes(StandardCharsets.UTF_8);

    private final FileValidationService service = new FileValidationService(UPLOAD_PROPERTIES);

    // ------------------------------------------------------------------------
    // Accepted
    // ------------------------------------------------------------------------

    @Test
    void acceptsAPdfWithTheExpectedExtensionAndContent() {
        assertThat(service.validate(upload("essay.pdf", PDF, PDF_BYTES))).isEmpty();
    }

    @Test
    void acceptsAnUppercaseExtension() {
        // Windows still writes .PDF in places, and refusing it would be a rule nobody could
        // explain to the student who hit it.
        assertThat(service.validate(upload("ESSAY.PDF", PDF, PDF_BYTES))).isEmpty();
    }

    @Test
    void acceptsAFileWhoseDeclaredContentTypeIsMissing() {
        // Not every client sends one. The extension and the magic bytes are the checks that
        // actually decide; an absent media type is not a reason to refuse a valid PDF.
        assertThat(service.validate(upload("essay.pdf", null, PDF_BYTES))).isEmpty();
    }

    @Test
    void acceptsAFileExactlyAtTheSizeLimit() {
        byte[] atTheLimit = pdfOfSize(UPLOAD_PROPERTIES.maxFileSizeBytes());

        assertThat(service.validate(upload("essay.pdf", PDF, atTheLimit))).isEmpty();
    }

    // ------------------------------------------------------------------------
    // Refused
    // ------------------------------------------------------------------------

    @Test
    void refusesAFileWithANonPdfExtension() {
        assertThat(service.validate(upload("essay.docx", PDF, PDF_BYTES)).orElseThrow())
                .contains("Only PDF files are accepted.");
    }

    @Test
    void refusesAFileWithNoExtension() {
        assertThat(service.validate(upload("essay", PDF, PDF_BYTES))).isPresent();
    }

    /**
     * The case the extension check cannot catch: a real filename, a plausible media type, and
     * content that is not a PDF. Only the magic-byte check refuses this.
     */
    @Test
    void refusesPdfExtensionOnContentThatIsNotAPdf() {
        assertThat(service.validate(upload("essay.pdf", PDF, NOT_A_PDF)).orElseThrow())
                .contains("That file is not a valid PDF.");
    }

    /**
     * A file whose content starts with the signature but is only four bytes long. It cannot carry
     * the full {@code %PDF-} header, and a check that only tested the first four bytes would
     * accept it.
     */
    @Test
    void refusesContentThatStopsShortOfTheFullSignature() {
        byte[] truncated = "%PDF".getBytes(StandardCharsets.UTF_8);

        assertThat(service.validate(upload("essay.pdf", PDF, truncated)).orElseThrow())
                .contains("That file is not a valid PDF.");
    }

    @Test
    void refusesAFileWhoseDeclaredContentTypeIsNotPdf() {
        // A browser reporting text/html for a .pdf is a signal worth refusing, even though the
        // client controls the value and the magic bytes are the real check.
        assertThat(service.validate(upload("essay.pdf", "text/html", PDF_BYTES)).orElseThrow())
                .contains("Only PDF files are accepted.");
    }

    @Test
    void refusesAFileLargerThanTheLimit() {
        byte[] tooLarge = pdfOfSize(UPLOAD_PROPERTIES.maxFileSizeBytes() + 1);

        // orElseThrow rather than asserting on the Optional directly: OptionalAssert.contains is
        // an equality check, so it would need the whole sentence. Unwrapping gives a String, for
        // which contains() is the substring comparison this test means.
        //
        // Compared against the sentence the configuration itself produces rather than against a
        // literal, so the assertion is "the refusal names the limit that was configured" rather
        // than "the refusal is still worded the way it was on the day this test was written".
        // UploadPropertiesTest separately pins how a limit is spelled.
        assertThat(service.validate(upload("essay.pdf", PDF, tooLarge)).orElseThrow())
                .isEqualTo(UPLOAD_PROPERTIES.oversizedMessage());
    }

    /**
     * The limit that is applied is the one that was configured, not the one that used to be a
     * constant.
     *
     * <p>The same bytes are refused by a one-mebibyte configuration and accepted by the shipped
     * ten-mebibyte one, which is what makes this a test of the setting rather than of the number.
     */
    @Test
    void appliesTheConfiguredLimit() {
        byte[] oneMebibyteAndOneByte = pdfOfSize(1024 * 1024 + 1);
        FileValidationService oneMegabyte = new FileValidationService(ONE_MEBIBYTE_LIMIT);

        assertThat(oneMegabyte.validate(upload("essay.pdf", PDF, oneMebibyteAndOneByte)).orElseThrow())
                .contains("larger than the 1 MB limit");
        assertThat(service.validate(upload("essay.pdf", PDF, oneMebibyteAndOneByte))).isEmpty();
    }

    @Test
    void refusesAnEmptyFile() {
        assertThat(service.validate(upload("essay.pdf", PDF, new byte[0])).orElseThrow())
                .contains("Choose a PDF to upload.");
    }

    @Test
    void refusesAFileWithNoName() {
        assertThat(service.validate(upload("", PDF, PDF_BYTES)).orElseThrow())
                .contains("no name");
    }

    /**
     * The form posts without a file when the input is left empty. It must produce a sentence a
     * student can act on rather than a framework 400.
     */
    @Test
    void refusesAMissingFile() {
        assertThat(service.validate(null).orElseThrow()).contains("Choose a PDF to upload.");
    }

    // ------------------------------------------------------------------------
    // Display names
    // ------------------------------------------------------------------------

    /**
     * A path supplied as a filename is reduced to its last segment.
     *
     * <p>The name is display metadata and is never used to locate anything on disk — that is
     * {@link StorageIdentifier}'s job — so this is defence in depth rather than the primary
     * control. The assertion is that no separator survives, whatever the client sent.
     */
    @Test
    void stripsDirectoryComponentsFromADisplayName() {
        assertThat(service.safeDisplayName("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(service.safeDisplayName("C:\\Users\\ayush\\essay.pdf")).isEqualTo("essay.pdf");
        assertThat(service.safeDisplayName("essay.pdf")).isEqualTo("essay.pdf");
    }

    /**
     * Control characters would otherwise reach a log line, where a newline can forge an entry,
     * or a response header, where one can split it.
     */
    @Test
    void removesControlCharactersFromADisplayName() {
        assertThat(service.safeDisplayName("es\r\nsay.pdf")).isEqualTo("essay.pdf");
        assertThat(service.safeDisplayName("essay\u0000.pdf")).isEqualTo("essay.pdf");
    }

    @Test
    void boundsADisplayNameToTheWidthOfTheColumn() {
        String name = "a".repeat(400) + ".pdf";

        String safe = service.safeDisplayName(name);

        assertThat(safe).hasSize(255);
        // Truncated from the front, so the part a person reads survives.
        assertThat(safe).endsWith(".pdf");
    }

    /**
     * A name made entirely of separators, or entirely of control characters, survives normalisation
     * as nothing at all. There is no such file name to display, so this is the one case that is
     * refused rather than repaired.
     */
    @Test
    void refusesADisplayNameWithNothingUsableInIt() {
        assertThatThrownBy(() -> service.safeDisplayName("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no usable characters");

        assertThatThrownBy(() -> service.safeDisplayName("\u0000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no usable characters");
    }

    private static MockMultipartFile upload(String fileName, String contentType, byte[] content) {
        return new MockMultipartFile("file", fileName, contentType, content);
    }

    /** Bytes of exactly the given length that still begin with the PDF signature. */
    private static byte[] pdfOfSize(long size) {
        byte[] content = new byte[Math.toIntExact(size)];
        System.arraycopy(PDF_BYTES, 0, content, 0, Math.min(PDF_BYTES.length, content.length));
        return content;
    }
}
