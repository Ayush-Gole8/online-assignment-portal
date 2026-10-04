package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vit.assignmentportal.auth.CurrentUserService;
import com.vit.assignmentportal.storage.FileValidationService;
import com.vit.assignmentportal.storage.UploadProperties;
import com.vit.assignmentportal.support.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * The controller behaviour that no request can reach.
 *
 * <p>An oversized upload is stopped by the servlet container's multipart parser, so
 * {@code MaxUploadSizeExceededException} is thrown before a handler is chosen — and {@code MockMvc}
 * does not run that parser at all. The handler below therefore cannot be exercised through the web
 * layer, which is the whole reason this class exists: without it, the one code path a real
 * oversized upload takes would be untested and the comment claiming otherwise would be a lie.
 *
 * <p>No Spring context. The controller is given a real {@link FileValidationService} over the same
 * committed defaults the application binds, because the property under test is that the container's
 * refusal and the validator's refusal say the same thing — and a mock would let the two agree by
 * construction rather than by accident.
 */
@Tag(TestCategories.UNIT)
class SubmissionControllerTest {

    /**
     * The limits as {@code application.yml} commits them.
     *
     * <p>Constructed directly rather than bound from a context so this test stays a unit test. The
     * separate question — whether the file in the repository actually binds to these values — is an
     * integration test's, and is asserted where the real context is loaded
     * ({@code UiRenderingIT.theCommittedUploadLimitsAreTheDocumentedOnes}).
     */
    private static final UploadProperties UPLOAD_PROPERTIES =
            new UploadProperties(DataSize.ofMegabytes(10), "application/pdf");

    private final FileValidationService fileValidationService =
            new FileValidationService(UPLOAD_PROPERTIES);

    private final SubmissionController controller = new SubmissionController(
            mock(SubmissionService.class), fileValidationService, mock(CurrentUserService.class));

    /**
     * The container's refusal must produce the <em>same sentence</em> the validator produces.
     *
     * <p>Asserting the literal string would freeze a message someone may reasonably reword.
     * Asserting equality with the validator's own output asserts the property that actually
     * matters: a student who uploads 11 MB and a student who uploads 10 MB and one byte must not be
     * told two different things about the same limit.
     *
     * <p>That property survived a change the literal would have hidden: the sentence is now derived
     * from the configured limit rather than written out in both places, so this test would fail if
     * either side stopped deriving it.
     */
    @Test
    void answersAnOversizedUploadWithTheMessageTheValidatorWouldProduce() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        String view = controller.oversizedUpload(redirectAttributes);

        assertThat(view).isEqualTo("redirect:/submissions/new");
        assertThat(redirectAttributes.getFlashAttributes().get("error"))
                .isEqualTo(messageTheValidatorProducesForAnOversizedFile());
    }

    /**
     * The refusal must not describe the file, the limit's enforcement point, or anything else the
     * caller did not already know ({@code CLAUDE.md} §7 rule 11 — errors expose no internals).
     */
    @Test
    void describesOnlyTheLimitAndNotTheContainer() {
        RedirectAttributesModelMap redirectAttributes = new RedirectAttributesModelMap();

        controller.oversizedUpload(redirectAttributes);

        Object message = redirectAttributes.getFlashAttributes().get("error");
        assertThat(message.toString())
                .doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("tomcat")
                .doesNotContainIgnoringCase("servlet")
                .doesNotContain("10*1024")
                .doesNotContain("10485760");
    }

    @Test
    void servesTheUploadForm() {
        assertThat(controller.uploadForm()).isEqualTo("submission/upload");
    }

    /** One byte over the limit, which the validator refuses on size before reading any content. */
    private static String messageTheValidatorProducesForAnOversizedFile() {
        byte[] oneByteOver = new byte[Math.toIntExact(
                UPLOAD_PROPERTIES.maxFileSizeBytes() + 1)];

        return new FileValidationService(UPLOAD_PROPERTIES)
                .validate(new MockMultipartFile("file", "essay.pdf",
                        UPLOAD_PROPERTIES.allowedContentType(), oneByteOver))
                .orElseThrow(() -> new AssertionError(
                        "The validator accepted a file over its own limit."));
    }
}
