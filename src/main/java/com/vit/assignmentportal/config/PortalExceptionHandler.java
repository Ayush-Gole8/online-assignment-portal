package com.vit.assignmentportal.config;

import com.vit.assignmentportal.common.PortalException;
import com.vit.assignmentportal.review.InvalidReviewException;
import com.vit.assignmentportal.storage.StorageException;
import com.vit.assignmentportal.submission.InvalidSubmissionException;
import com.vit.assignmentportal.submission.SubmissionAlreadyDecidedException;
import com.vit.assignmentportal.submission.SubmissionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * The one place an application failure becomes an HTTP response.
 *
 * <h2>What this class is for</h2>
 *
 * <p>Every exception in the application's vocabulary is mapped to a status here, so the taxonomy can
 * be read in one screen instead of being inferred from {@code @ResponseStatus} annotations scattered
 * across three packages. Each feature still <em>raises</em> its own failure — that is its business —
 * but what a failure means over HTTP is a web-layer decision, and it is made once.
 *
 * <h2>This is the last resort, not the first response</h2>
 *
 * <p>These handlers are reached only by a failure that <strong>escaped a controller's own
 * handling</strong>. The upload form and the decision form both catch their refusals and re-render
 * the page with a sentence next to the field it is about; that path is far better than a generic
 * error page, and it is untouched by anything here. A controller's own {@code @ExceptionHandler}
 * also wins over this class. So what arrives below is the residue: a stale bookmark, a hand-edited
 * identifier, a race between two reviewers, a volume that has gone away.
 *
 * <h2>Nothing internal is ever rendered</h2>
 *
 * <p>The response is the same {@code error} view a 404 or a 500 has always rendered, and it shows
 * the status and a sentence and nothing else — no exception class, no stack trace, no message, no
 * path ({@code CLAUDE.md} §7 rule 11). That is true even for a {@link PortalException}, whose
 * message is safe to render: putting one on a generic error page would describe an internal
 * decision to a reader who cannot act on it, and the sentence already reached them on the form they
 * came from. {@link StorageException} is the case that matters most — its message names a storage
 * identifier, and this class renders no message at all, so there is nothing to redact.
 *
 * <p>The failure itself goes to the log with the request identifier
 * ({@code X-Request-ID}, contributed by {@link RequestIdFilter} through the logging context), which
 * is where an operator can act on it and where a user cannot see it.
 *
 * <h2>What is deliberately not handled</h2>
 *
 * <p><strong>Authorization.</strong> Anonymous and role-based refusals never reach MVC — they are
 * answered by Spring Security's filter chain, which runs before {@code DispatcherServlet} and
 * redirects to {@code /login} or forwards to {@code /access-denied} in
 * {@link SecurityConfiguration}. A handler here would be a second, weaker copy of a policy that is
 * already enforced, and catching {@code AccessDeniedException} at this layer risks turning a 403
 * into a 500. It stays where it is.
 *
 * <p><strong>Framework failures.</strong> A malformed path variable, a missing request parameter
 * and an unmapped URL are the framework's to answer, and it already does so with the right status.
 * Only this application's own vocabulary is translated here.
 */
@ControllerAdvice
public class PortalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PortalExceptionHandler.class);

    /**
     * A submission the caller does not hold — either it does not exist, or it is somebody else's.
     *
     * <p>Logged at {@code WARN} rather than {@code DEBUG} because it is also what a hand-edited
     * identifier looks like. The two are indistinguishable here by design — the 404 that hides the
     * difference is the same one that hides it from the caller — so the log line is the only place
     * the distinction between "a stale link" and "somebody walking the range" could ever be seen,
     * and it should not be silent.
     */
    @ExceptionHandler(SubmissionNotFoundException.class)
    public ModelAndView submissionNotFound(SubmissionNotFoundException failure) {
        log.warn("Refused a request for a submission the caller does not hold: {}",
                failure.getMessage());
        return errorPage(HttpStatus.NOT_FOUND);
    }

    /**
     * A request the application's own validation refused.
     *
     * <p>Normally caught by the controller that owns the form and shown beside the field; reaching
     * here means there was no form to return to, so the client sent something this application will
     * not process and cannot fix up.
     */
    @ExceptionHandler({InvalidSubmissionException.class, InvalidReviewException.class})
    public ModelAndView requestRefused(PortalException failure) {
        log.warn("Refused a malformed request: {}", failure.getMessage());
        return errorPage(HttpStatus.BAD_REQUEST);
    }

    /**
     * A decision against work that is already decided (FR-20).
     *
     * <p>{@code 409 Conflict} rather than {@code 400}: the request was well-formed, and it is the
     * state of the resource that refuses it. The same code answers the concurrent case below, and
     * for the same reason — in both, somebody else's decision already stands.
     */
    @ExceptionHandler(SubmissionAlreadyDecidedException.class)
    public ModelAndView alreadyDecided(SubmissionAlreadyDecidedException failure) {
        log.warn("Refused a decision on decided work: {}", failure.getMessage());
        return errorPage(HttpStatus.CONFLICT);
    }

    /**
     * Two writers changed the same row, and this transaction was the loser.
     *
     * <p>Caught by Spring's provider-neutral {@code OptimisticLockingFailureException} rather than
     * the Hibernate-specific subclass, so this mapping does not depend on which persistence provider
     * raised it. The row's {@code @Version} column is what produces it.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ModelAndView concurrentUpdate(OptimisticLockingFailureException failure) {
        log.warn("Refused a write whose row had been changed by another transaction.", failure);
        return errorPage(HttpStatus.CONFLICT);
    }

    /**
     * The volume could not be written to or read from.
     *
     * <p>The one handler that logs at {@code ERROR} with the throwable attached: this is not a
     * request the user got wrong, it is infrastructure that needs an operator. The identifier in the
     * message is exactly what that operator needs, and it stops at the log.
     */
    @ExceptionHandler(StorageException.class)
    public ModelAndView storageFailure(StorageException failure) {
        log.error("A file-storage operation failed.", failure);
        return errorPage(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Any other refusal this application raises.
     *
     * <p>Spring chooses the most specific handler, so this is only reached by a {@link PortalException}
     * subclass that has no mapping of its own. It exists so that adding one cannot accidentally
     * bypass the vocabulary: without it, a new refusal would fall through to the framework's error
     * path rather than to the model this class states. {@code 400} is the right default for a
     * refusal — the alternative, a 500, would blame the server for a request it declined.
     */
    @ExceptionHandler(PortalException.class)
    public ModelAndView portalRefusal(PortalException failure) {
        log.warn("Refused a request: {}", failure.getMessage());
        return errorPage(HttpStatus.BAD_REQUEST);
    }

    /**
     * The response every failure above answers with: the shared error page, carrying the status.
     *
     * <p>{@code status} is the only model attribute the template reads, and it is the only part of
     * a failure that is not an internal detail — the page tells a reader whether the address was
     * wrong or whether something broke, which is the same number their browser already received.
     */
    private static ModelAndView errorPage(HttpStatus status) {
        ModelAndView modelAndView = new ModelAndView("error");
        modelAndView.setStatus(status);
        modelAndView.addObject("status", status.value());
        return modelAndView;
    }
}
