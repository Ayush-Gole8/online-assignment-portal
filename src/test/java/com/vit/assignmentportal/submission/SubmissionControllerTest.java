package com.vit.assignmentportal.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vit.assignmentportal.auth.CurrentUserService;
import com.vit.assignmentportal.storage.FileValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
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
 * <p>No Spring context. The handler reads nothing from the controller's collaborators, so a
 * context would add a second of startup and prove nothing.
 */
class SubmissionControllerTest {

    private final SubmissionController controller = new SubmissionController(
            mock(SubmissionService.class), mock(CurrentUserService.class));

    /**
     * The container's refusal must produce the <em>same sentence</em> the validator produces.
     *
     * <p>Asserting the literal string would freeze a message someone may reasonably reword.
     * Asserting equality with the validator's own output asserts the property that actually
     * matters: a student who uploads 11 MB and a student who uploads 10 MB and one byte must not be
     * told two different things about the same limit.
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
                FileValidationService.MAX_FILE_SIZE_BYTES + 1)];

        return new FileValidationService()
                .validate(new MockMultipartFile("file", "essay.pdf",
                        FileValidationService.PDF_CONTENT_TYPE, oneByteOver))
                .orElseThrow(() -> new AssertionError(
                        "The validator accepted a file over its own limit."));
    }
}
