package com.vit.assignmentportal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vit.assignmentportal.support.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * The upload configuration's own rules: what it refuses, and how it spells a limit.
 *
 * <p>A record's compact constructor is the only code this type has, and it is the code that decides
 * whether the application starts. That makes it worth testing directly rather than only through the
 * validator that consumes it: a bad binding must fail at startup with a sentence naming the
 * environment variable, not at the first upload with a sentence for a student.
 *
 * <p>No Spring context. Binding itself — that {@code app.upload.max-file-size} in
 * {@code application.yml} reaches this record — is an integration test's question, and is asserted
 * where the real context is loaded.
 */
@Tag(TestCategories.UNIT)
class UploadPropertiesTest {

    @Test
    void spellsALimitInTheLargestBinaryUnitItDividesInto() {
        assertThat(properties(10, 1024 * 1024).oversizedMessage())
                .isEqualTo("The file is larger than the 10 MB limit.");
        assertThat(properties(1, 1024 * 1024).oversizedMessage())
                .isEqualTo("The file is larger than the 1 MB limit.");
        assertThat(properties(512, 1024).oversizedMessage())
                .isEqualTo("The file is larger than the 512 KB limit.");
    }

    /**
     * A limit that is not a whole number of kilobytes must not be rounded into one.
     *
     * <p>Reporting "0 KB" — or worse, "0 MB" — would tell a student that a file larger than the
     * limit is larger than nothing, which is a sentence no one can act on.
     */
    @Test
    void doesNotRoundALimitThatIsNotAWholeUnit() {
        assertThat(properties(1500, 1).oversizedMessage())
                .isEqualTo("The file is larger than the 1500 bytes limit.");
    }

    @Test
    void reportsTheLimitInBytes() {
        assertThat(properties(10, 1024 * 1024).maxFileSizeBytes()).isEqualTo(10L * 1024 * 1024);
    }

    /**
     * The accepted media type is normalised, because a media type is case-insensitive and
     * {@code Application/PDF} is the same format as {@code application/pdf}.
     */
    @Test
    void normalisesTheAcceptedMediaType() {
        assertThat(new UploadProperties(DataSize.ofMegabytes(10), "  Application/PDF  ")
                .allowedContentType())
                .isEqualTo(UploadProperties.PDF_CONTENT_TYPE);
    }

    // ------------------------------------------------------------------------
    // Startup refusals
    //
    // Each of these stops the application rather than surfacing at the first upload. The message is
    // what an operator has to work from, so each test asserts that it names the problem.
    // ------------------------------------------------------------------------

    @Test
    void refusesAMissingSizeLimit() {
        assertThatThrownBy(() -> new UploadProperties(null, UploadProperties.PDF_CONTENT_TYPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.upload.max-file-size")
                .hasMessageContaining("UPLOAD_MAX_FILE_SIZE");
    }

    @Test
    void refusesASizeLimitOfZero() {
        assertThatThrownBy(() -> new UploadProperties(DataSize.ofBytes(0),
                UploadProperties.PDF_CONTENT_TYPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one byte");
    }

    @Test
    void refusesAMissingMediaType() {
        assertThatThrownBy(() -> new UploadProperties(DataSize.ofMegabytes(10), "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.upload.allowed-content-type")
                .hasMessageContaining("UPLOAD_ALLOWED_CONTENT_TYPE");
    }

    /**
     * A second format is refused at startup rather than accepted and then rejected on every upload.
     *
     * <p>The signature check that actually proves a file's format is written for PDF, so naming a
     * different media type would not widen what the portal accepts — it would make every upload fail
     * the last check with "That file is not a valid PDF.". The refusal explains that, which is the
     * only moment at which whoever changed the value can act on it.
     */
    @Test
    void refusesAFormatTheContentCheckCannotProve() {
        assertThatThrownBy(() -> new UploadProperties(DataSize.ofMegabytes(10), "image/png"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("image/png")
                .hasMessageContaining("%PDF-");
    }

    private static UploadProperties properties(long amount, int unitInBytes) {
        return new UploadProperties(DataSize.ofBytes(amount * unitInBytes),
                UploadProperties.PDF_CONTENT_TYPE);
    }
}
