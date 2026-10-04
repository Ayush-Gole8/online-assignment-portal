package com.vit.assignmentportal.review;

import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.auth.CurrentUserService;
import com.vit.assignmentportal.common.SubmissionStatus;
import com.vit.assignmentportal.submission.SubmissionAlreadyDecidedException;
import com.vit.assignmentportal.submission.SubmissionFileAttachment;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The reviewer's pages: the queue, one submission, the decision, and the file.
 *
 * <h2>Authorisation is the route rule, not code in this class</h2>
 *
 * <p>Every path here is under {@code /review}, which {@code SecurityConfiguration} restricts to
 * {@code REVIEWER} and {@code ADMIN}. There is no role check in any handler below, deliberately: a
 * second copy of the policy is a second thing to keep in step, and a handler added later would
 * have to remember to repeat it. The filter chain cannot be forgotten, and the tests assert it
 * rather than assume it — {@code auth/AuthorizationIT} checks the route rule directly, and
 * {@code ReviewWorkflowIT} drives every review route as a {@code STUDENT} and as an anonymous
 * visitor to prove none of them is reachable.
 *
 * <p>Because nothing below can run without a principal, {@link CurrentUserService#requireCurrentUser()}
 * is safe to call unconditionally — and the reviewer's identifier comes from there rather than from
 * a form field, so the actor recorded against a decision is never a value the request chose
 * ({@code CLAUDE.md} §7 rule 8).
 *
 * <h2>The decision is a POST that redirects</h2>
 *
 * <p>Recording a decision answers {@code 303 See Other} back to the submission, so a refresh does
 * not attempt the decision again — which matters more here than for an upload, because the second
 * attempt would be refused as already-decided and the reviewer would see an error for an action
 * that had in fact succeeded. The three failures are handled the same way, as a flash message on
 * the page the reviewer came from.
 *
 * <h2>Reviewers cannot change the artefact</h2>
 *
 * <p>There is no upload, no edit, and no delete handler on this controller, and the reviewer's
 * template contains no form that posts a file. A reviewer reads the work and records a decision;
 * the only state they can change is the status, through {@link #decide}.
 */
@Controller
@RequestMapping("/review")
public class ReviewController {

    /**
     * How many submissions one page of the queue holds.
     *
     * <p>Twenty, chosen for the page rather than for the query: a queue is worked through, and a
     * page that fits on a screen without the pager being scrolled past is a page a reviewer can
     * finish. It is a presentation constant, so it lives with the controller that renders the page
     * rather than in configuration.
     */
    private static final int QUEUE_PAGE_SIZE = 20;

    private final ReviewService reviewService;
    private final CurrentUserService currentUserService;

    public ReviewController(ReviewService reviewService, CurrentUserService currentUserService) {
        this.reviewService = reviewService;
        this.currentUserService = currentUserService;
    }

    /**
     * The queue of submissions awaiting a decision (FR-16).
     *
     * <p>Mapped at both {@code /review} and {@code /review/queue}. The bare path is the original and
     * is the one the home page and the navigation have always linked to, so it stays; {@code
     * /review/queue} names the page the way the rest of the site names its pages, and is what the
     * navigation bar now points at. Two paths, one handler — a redirect from one to the other would
     * only add a round trip.
     *
     * <p>{@code page} is clamped at zero rather than validated upward. A negative value is not
     * something a link produces; it arrives from a hand-edited address bar, and the choice is
     * between the first page and a 500. This is a bound, not a business rule.
     */
    @GetMapping({"", "/queue"})
    public String queue(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        PageRequest request = PageRequest.of(Math.max(page, 0), QUEUE_PAGE_SIZE);
        model.addAttribute("submissions", reviewService.queue(request));
        return "review/queue";
    }

    /**
     * One submission, with its history and the decision form (FR-17).
     *
     * @throws com.vit.assignmentportal.submission.SubmissionNotFoundException rendered as a 404
     */
    @GetMapping("/submissions/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        model.addAttribute("submission", reviewService.view(id));
        model.addAttribute("history", reviewService.history(id));
        return "review/detail";
    }

    /**
     * Records the reviewer's decision (FR-18, FR-19, FR-20).
     *
     * <p>Both parameters are {@code required = false}. A decision posted with no outcome, or a
     * rejection posted with an empty reason, is something a client can do — and each deserves the
     * sentence {@link ReviewService} produces rather than a framework 400 that names a missing
     * request parameter.
     *
     * <p>The three failures caught here are genuinely different events, and each is reported with
     * its own message:
     *
     * <ul>
     *   <li>{@link InvalidReviewException} — the decision was malformed. The reviewer can fix it on
     *       the form.</li>
     *   <li>{@link SubmissionAlreadyDecidedException} — somebody else decided it first, or this
     *       page was stale. Nothing to fix; the work is done.</li>
     *   <li>{@link OptimisticLockingFailureException} — two reviewers decided concurrently and this
     *       transaction was the loser. The other decision stands, and this one changed nothing.
     *       Caught by its Spring Data superclass rather than the Hibernate-specific subclass, so
     *       the handler does not depend on which provider raised it.</li>
     * </ul>
     *
     * <p>That last case is the one the optimistic lock exists for. Without
     * {@code Submission.version}, the second writer would overwrite the first reviewer's decision
     * and the student would be told an outcome nobody recorded.
     */
    @PostMapping("/submissions/{id}")
    public String decide(@PathVariable UUID id,
                         @RequestParam(name = "outcome", required = false) SubmissionStatus outcome,
                         @RequestParam(name = "note", required = false) String note,
                         RedirectAttributes redirectAttributes) {

        AuthenticatedUser reviewer = currentUserService.requireCurrentUser();

        try {
            reviewService.decide(reviewer.id(), id, new ReviewDecision(outcome, note));
            redirectAttributes.addFlashAttribute("decided", outcome);
        } catch (InvalidReviewException refusal) {
            redirectAttributes.addFlashAttribute("error", refusal.getMessage());
            // Handed back so a reviewer who wrote a long reason and chose the wrong outcome does
            // not lose it to the redirect.
            redirectAttributes.addFlashAttribute("note", note);
        } catch (SubmissionAlreadyDecidedException alreadyDecided) {
            redirectAttributes.addFlashAttribute("error", alreadyDecided.getMessage());
        } catch (OptimisticLockingFailureException concurrentDecision) {
            redirectAttributes.addFlashAttribute("error",
                    "Another reviewer decided this submission while you were reading it. "
                            + "Their decision stands and yours was not recorded.");
        }

        return "redirect:/review/submissions/" + id;
    }

    /**
     * The submitted file, so a reviewer can read the work (FR-17).
     *
     * <p>Served as an attachment with the media type that was validated at upload, never inline
     * ({@code CLAUDE.md} §7 rule 10): a PDF rendered inline executes in this application's origin,
     * and an attachment does not. {@code nosniff} stops a browser from second-guessing the type it
     * was given.
     *
     * <p>The file name offered is {@code original_file_name}, already stripped of directory
     * components and control characters by {@code FileValidationService.safeDisplayName} on the way
     * in — which is what makes it safe to put in a header. The storage identifier is never
     * disclosed.
     */
    @GetMapping("/submissions/{id}/file")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        return SubmissionFileAttachment.of(reviewService.download(id));
    }
}
