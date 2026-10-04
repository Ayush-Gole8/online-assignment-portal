package com.vit.assignmentportal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.vit.assignmentportal.common.PortalException;
import com.vit.assignmentportal.review.InvalidReviewException;
import com.vit.assignmentportal.storage.StorageException;
import com.vit.assignmentportal.submission.InvalidSubmissionException;
import com.vit.assignmentportal.submission.SubmissionAlreadyDecidedException;
import com.vit.assignmentportal.submission.SubmissionNotFoundException;
import com.vit.assignmentportal.support.TestCategories;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

/**
 * The failure vocabulary: what each exception becomes over HTTP, and what it must never become.
 *
 * <p>No Spring context. The class under test is an adapter — a mapping table and one private helper —
 * and every claim below is about that table. A context would add a second of startup per test and
 * would exercise {@code @ExceptionHandler} selection, which is the framework's behaviour and not
 * this application's; the end-to-end path is asserted where a real request is made
 * ({@code UiRenderingIT}, {@code SubmissionAccessIT}).
 *
 * <p>The two cross-cutting tests at the bottom are the ones worth keeping if everything else here
 * were deleted. Each row of the table can be checked by reading it, but "every failure renders the
 * same view" and "no failure puts anything but a number in the model" cannot be, and they are the
 * assertions {@code CLAUDE.md} §7 rule 11 actually rests on.
 */
@Tag(TestCategories.UNIT)
class PortalExceptionHandlerTest {

    private static final PortalExceptionHandler HANDLER = new PortalExceptionHandler();

    // ------------------------------------------------------------------------
    // The mapping table
    // ------------------------------------------------------------------------

    @Test
    void answersASubmissionTheCallerDoesNotHoldWith404() {
        ModelAndView answer = HANDLER.submissionNotFound(
                SubmissionNotFoundException.forRequestedSubmission());

        assertThat(answer.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * {@code 400} rather than {@code 404} for a malformed identifier, and rather than {@code 500}.
     *
     * <p>The distinction is the reason both codes exist here: this is a client that sent something
     * the application will not process, not an address that names nothing.
     */
    @Test
    void answersARefusedUploadWith400() {
        ModelAndView answer = HANDLER.requestRefused(
                new InvalidSubmissionException("A title is required.", "title"));

        assertThat(answer.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void answersARefusedDecisionWith400() {
        ModelAndView answer = HANDLER.requestRefused(
                new InvalidReviewException("A rejection needs a reason."));

        assertThat(answer.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * {@code 409} rather than {@code 400}: the request was well-formed and it is the state of the
     * resource that refuses it. A reviewer who hits this cannot fix it by editing the form, which is
     * exactly what the two codes are meant to tell a client apart.
     */
    @Test
    void answersADecisionOnDecidedWorkWith409() {
        ModelAndView answer = HANDLER.alreadyDecided(
                SubmissionAlreadyDecidedException.forDecidedSubmission());

        assertThat(answer.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * The concurrent case answers the same code as the sequential one, because from the loser's side
     * they are the same event: somebody else's decision already stands.
     */
    @Test
    void answersAWriteThatLostARaceWith409() {
        ModelAndView answer = HANDLER.concurrentUpdate(
                new OptimisticLockingFailureException("Row was updated by another transaction."));

        assertThat(answer.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    /**
     * The one non-client failure, and the only {@code 500}.
     *
     * <p>A volume that has gone away is not a request the user got wrong. Blaming them with a 400
     * would send them to edit a form that is not the problem.
     */
    @Test
    void answersAStorageFailureWith500() {
        ModelAndView answer = HANDLER.storageFailure(
                new StorageException("The file could not be read.", null));

        assertThat(answer.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * A refusal with no mapping of its own still lands on the model this class states.
     *
     * <p>Spring picks the most specific handler, so this is the fallback: a {@link PortalException}
     * subclass added later, with no {@code @ExceptionHandler} of its own, must answer a refusal
     * rather than fall through to the framework's error path.
     */
    @Test
    void answersARefusalWithNoMappingOfItsOwnWith400() {
        ModelAndView answer = HANDLER.portalRefusal(new UnmappedRefusal("A refusal added later."));

        assertThat(answer.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------------
    // Properties that hold for every row of the table
    // ------------------------------------------------------------------------

    /**
     * Every failure renders the one shared error page.
     *
     * <p>Asserted across the whole vocabulary rather than per handler because a status with the
     * wrong view is worse than no handler at all: it would render some other page's markup with an
     * error's status. A future handler added without this property would fail here.
     */
    @Test
    void rendersTheSharedErrorViewForEveryFailure() {
        everyFailure().forEach((description, answer) ->
                assertThat(answer.getViewName())
                        .as("%s should render the shared error page", description)
                        .isEqualTo("error"));
    }

    /**
     * The status code is the only thing a failure puts in the model.
     *
     * <p>This is {@code CLAUDE.md} §7 rule 11 stated as an assertion. A handler that added
     * {@code message}, {@code exception} or {@code path} to the model would pass every test above
     * and leak an internal — and this is the only test that would notice, because the leak is in the
     * model rather than in the status. The error page reads {@code status} and nothing else, which is
     * what the single key records.
     */
    @Test
    void putsNothingButTheStatusCodeInTheModel() {
        everyFailure().forEach((description, answer) ->
                assertThat(answer.getModel())
                        .as("%s should expose the status and no internal detail", description)
                        .containsOnlyKeys("status"));
    }

    /**
     * A storage failure's message names a path, and the path does not reach the response.
     *
     * <p>The general rule above says the model has one key; this says what that key does not
     * contain. It is the sharpest case in the vocabulary — {@link StorageException} is the one
     * exception whose message is written for an operator and never for a user, and it is the one a
     * well-meaning change is most likely to start rendering.
     */
    @Test
    void rendersNoPartOfAStorageFailureMessageOrItsType() {
        String storagePath = "/srv/portal/uploads/ab12cd34.pdf";

        ModelAndView answer = HANDLER.storageFailure(new StorageException(
                "The stored file " + storagePath + " could not be read.", null));

        assertThat(answer.getModel().values().toString())
                .doesNotContain(storagePath)
                .doesNotContain("StorageException");
    }

    /**
     * Every failure this application raises, paired with the sentence that describes it — used by
     * the cross-cutting tests above so that a mapping added to the handler is covered the moment it
     * is added, without a second edit here.
     */
    private static Map<String, ModelAndView> everyFailure() {
        Map<String, ModelAndView> answers = new LinkedHashMap<>();
        answers.put("a submission the caller does not hold",
                HANDLER.submissionNotFound(SubmissionNotFoundException.forRequestedSubmission()));
        answers.put("a refused upload", HANDLER.requestRefused(
                new InvalidSubmissionException("A title is required.", "title")));
        answers.put("a refused decision",
                HANDLER.requestRefused(new InvalidReviewException("A rejection needs a reason.")));
        answers.put("a decision on decided work",
                HANDLER.alreadyDecided(SubmissionAlreadyDecidedException.forDecidedSubmission()));
        answers.put("a write that lost a race", HANDLER.concurrentUpdate(
                new OptimisticLockingFailureException("Row was updated by another transaction.")));
        answers.put("a storage failure",
                HANDLER.storageFailure(new StorageException("The file could not be read.", null)));
        answers.put("a refusal with no mapping of its own",
                HANDLER.portalRefusal(new UnmappedRefusal("A refusal added later.")));
        return answers;
    }

    /**
     * Stands in for a {@link PortalException} subclass that has no {@code @ExceptionHandler} of its
     * own — the case the fallback exists for. It is declared here rather than exercised through a
     * real one because a real one would have to be added to a feature to be a test subject, which
     * would put a class in the codebase for the test's benefit.
     */
    private static final class UnmappedRefusal extends PortalException {

        private UnmappedRefusal(String message) {
            super(message);
        }
    }
}
