package com.vit.assignmentportal.submission;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * No submission with that identifier belongs to the requesting student.
 *
 * <p>Raised for a submission that does not exist <em>and</em> for one that exists but belongs to
 * somebody else. The two are deliberately indistinguishable: answering 403 for the second case
 * would confirm that a given identifier is real, turning the detail page into an oracle for
 * discovering which submissions exist. A uniform 404 leaks nothing, and the ownership clause in
 * the query is what makes "not yours" collapse into "not found" without a special case.
 *
 * <p>{@code @ResponseStatus} rather than a handler in the controller: a 404 for a missing
 * resource is the same answer wherever it is raised, and there is no view to render — the page
 * simply is not there.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class SubmissionNotFoundException extends RuntimeException {

    public SubmissionNotFoundException(String message) {
        super(message);
    }

    /**
     * The message used by every call site. It names no identifier, because the message reaches a
     * log an operator reads and there is nothing to be gained from echoing a value the caller
     * already knows — it was in the URL.
     */
    public static SubmissionNotFoundException forRequestedSubmission() {
        return new SubmissionNotFoundException(
                "No submission with that identifier belongs to the signed-in student.");
    }
}
