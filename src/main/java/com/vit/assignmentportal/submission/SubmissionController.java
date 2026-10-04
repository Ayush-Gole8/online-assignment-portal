package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.auth.CurrentUserService;
import com.vit.assignmentportal.storage.FileValidationService;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The student's pages: upload a file, see what has been handed in, open one of them, download it.
 *
 * <h2>Every handler ends in a redirect, including the failures</h2>
 *
 * <p>The upload is a POST and answers with {@code 303 See Other} to a GET, so a refresh after
 * uploading does not resubmit the file. The refusals redirect too — back to the form, carrying
 * the message as a flash attribute — which is what lets a browser reload the error page without
 * trying the upload again, and what keeps one code path for "it worked" and "it did not".
 *
 * <h2>Authorisation happens in the service, not here</h2>
 *
 * <p>This class does not check whether the submission being opened belongs to the caller. It
 * passes the caller's identifier to {@link SubmissionService#detailForStudent}, which looks the
 * submission up <em>by owner</em>. A check written here would be a check that the next controller
 * has to remember to repeat; a query that cannot return somebody else's row does not.
 *
 * <h2>What this class no longer does</h2>
 *
 * <p>It does not put the signed-in user in the model — {@code config.CurrentUserModelAdvice} does
 * that for every page — and it does not translate a page of submissions into read models, which
 * {@link SubmissionService} now does inside the transaction that read them. What is left is what a
 * controller should be: it reads a request, calls one service method, names a view.
 *
 * <p>The route is guarded by {@code anyRequest().authenticated()} in {@code
 * SecurityConfiguration}, so no handler below can run without a principal — which is what makes
 * {@link CurrentUserService#requireCurrentUser()} safe to call unconditionally.
 */
@Controller
@RequestMapping("/submissions")
public class SubmissionController {

    /**
     * How many submissions one page of the student's list holds.
     *
     * <p>Ten: the list is a record of what the student has handed in, and a screenful is enough to
     * find the piece they came for before paging. A presentation constant, so it lives here with the
     * page it sizes rather than in configuration.
     */
    private static final int LIST_PAGE_SIZE = 10;

    private final SubmissionService submissionService;
    private final FileValidationService fileValidationService;
    private final CurrentUserService currentUserService;

    public SubmissionController(SubmissionService submissionService,
                                FileValidationService fileValidationService,
                                CurrentUserService currentUserService) {
        this.submissionService = submissionService;
        this.fileValidationService = fileValidationService;
        this.currentUserService = currentUserService;
    }

    /** The upload form. */
    @GetMapping("/new")
    public String uploadForm() {
        return "submission/upload";
    }

    /**
     * Stores an upload and shows the student the result.
     *
     * <p>The file part is {@code required = false} on purpose. A missing part is a thing a user
     * can do — submitting the form without choosing a file — and it deserves the same sentence as
     * every other bad upload rather than a framework-generated 400 that says
     * {@code Required request part 'file' is not present}.
     *
     * @return a redirect to the new submission, or back to the form with a reason
     */
    @PostMapping
    public String submit(@RequestParam(name = "file", required = false) MultipartFile file,
                         @RequestParam(name = "title", required = false) String title,
                         @RequestParam(name = "courseCode", required = false) String courseCode,
                         RedirectAttributes redirectAttributes) {

        AuthenticatedUser student = currentUserService.requireCurrentUser();

        try {
            Submission submission = submissionService.submit(student, file, title, courseCode);
            redirectAttributes.addFlashAttribute("submitted", submission.getOriginalFileName());
            // The identifier goes in the URL, not in a form field, and it was generated by the
            // server. Nothing the client sent decided which submission was created.
            return "redirect:/submissions/" + submission.getId();
        } catch (InvalidSubmissionException refusal) {
            // What the student typed is handed back so the form is not emptied by an error they
            // have to correct. The file is not: a browser cannot repopulate a file input, and
            // saying otherwise would be a lie the page then contradicts.
            redirectAttributes.addFlashAttribute("error", refusal.getMessage());
            // The field the refusal is about, so the form can mark that input rather than only
            // showing the sentence above the form. Null when the refusal names no single field, and
            // the template treats that as "show the message, mark nothing".
            redirectAttributes.addFlashAttribute("errorField", refusal.field());
            redirectAttributes.addFlashAttribute("title", title);
            redirectAttributes.addFlashAttribute("courseCode", courseCode);
            return "redirect:/submissions/new";
        }
    }

    /**
     * One page of the signed-in student's own submissions.
     *
     * <p>The page is read from the database rather than sliced in memory, so a student with years of
     * coursework costs the same to render as one with three pieces. {@code page} is clamped at zero
     * because a negative value can only arrive from a hand-edited address bar, and the choice there
     * is between the first page and a 500 — a bound, not a business rule.
     */
    @GetMapping
    public String list(@RequestParam(name = "page", defaultValue = "0") int page, Model model) {
        AuthenticatedUser student = currentUserService.requireCurrentUser();

        PageRequest request = PageRequest.of(Math.max(page, 0), LIST_PAGE_SIZE);
        model.addAttribute("submissions", submissionService.listForStudent(student.id(), request));

        return "submission/list";
    }

    /**
     * One of the signed-in student's own submissions.
     *
     * <p>Carries the submission's transitions as well as its current state, so the page can show
     * the chronological history alongside the decision (FR-15, FR-21). Like the submission itself,
     * the history is fetched <em>by owner</em>: {@code historyForStudent} looks the submission up
     * with the caller's identifier first, so another student's history cannot be reached.
     *
     * @throws SubmissionNotFoundException if it does not exist or is not theirs, which renders as
     *                                     a 404 either way
     */
    @GetMapping("/{id}")
    public String detail(@PathVariable UUID id, Model model) {
        AuthenticatedUser student = currentUserService.requireCurrentUser();

        model.addAttribute("submission", submissionService.detailForStudent(student.id(), id));
        model.addAttribute("history", submissionService.historyForStudent(student.id(), id));

        return "submission/detail";
    }

    /**
     * The student's own uploaded file, so they can check what they handed in (FR-13).
     *
     * <p>Ownership is enforced by {@code downloadForStudent}, which resolves the submission with
     * the caller's identifier — the same query-not-check rule as {@link #detail}. A submission
     * that is not theirs does not exist, and the missing file follows from that rather than from a
     * separate decision here.
     *
     * <p>The response is built by {@link SubmissionFileAttachment}, which is also what the
     * reviewer's route uses: an attachment, a fixed {@code application/pdf}, and {@code nosniff}
     * ({@code CLAUDE.md} §7 rule 10). The storage identifier is never disclosed — only the
     * normalised original file name.
     *
     * @throws SubmissionNotFoundException if it does not exist or is not theirs
     * @throws StorageException if the row exists but the bytes on the volume do not
     */
    @GetMapping("/{id}/file")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        AuthenticatedUser student = currentUserService.requireCurrentUser();
        return SubmissionFileAttachment.of(submissionService.downloadForStudent(student.id(), id));
    }

    /**
     * The container refused the upload before this application saw it.
     *
     * <p>{@code spring.servlet.multipart.max-file-size} stops an oversized request at the servlet
     * layer, so it never reaches {@link #submit} and never reaches
     * {@code FileValidationService.validate} — which is the point of having the limit there: the
     * bytes are never buffered. The cost is that the refusal arrives as an exception rather than as
     * a message, so it is translated here into the same sentence the validator would have produced.
     * That sentence is asked for rather than written out: it is derived from the configured limit,
     * and a copy of it here would go on saying "10 MB" after the limit was lowered.
     *
     * <p>This handler is unreachable through {@code MockMvc}, which does not run the container's
     * multipart parsing. It is covered by a unit test that throws the exception directly.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String oversizedUpload(RedirectAttributes redirectAttributes) {
        redirectAttributes.addFlashAttribute("error",
                fileValidationService.oversizedFileMessage());
        return "redirect:/submissions/new";
    }
}
