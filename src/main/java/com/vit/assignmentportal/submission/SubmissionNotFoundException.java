package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.common.PortalException;

/**
 * No submission with that identifier belongs to the requesting student.
 *
 * <p>Raised for a submission that does not exist <em>and</em> for one that exists but belongs to
 * somebody else. The two are deliberately indistinguishable: answering 403 for the second case
 * would confirm that a given identifier is real, turning the detail page into an oracle for
 * discovering which submissions exist. A uniform 404 leaks nothing, and the ownership clause in
 * the query is what makes "not yours" collapse into "not found" without a special case.
 *
 * <p><strong>The 404 is not declared here.</strong> It used to be a {@code @ResponseStatus} on this
 * class, which put the mapping in the feature and the response in the framework, with no single
 * place stating the whole vocabulary. The status now belongs to the one handler that translates
 * every {@link PortalException} into a response — {@code config.PortalExceptionHandler} — so this
 * class says what happened and the web layer says what that means over HTTP. The handler still
 * answers 404.
 */
public class SubmissionNotFoundException extends PortalException {

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
