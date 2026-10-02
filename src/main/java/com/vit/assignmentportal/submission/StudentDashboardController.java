package com.vit.assignmentportal.submission;

import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.auth.CurrentUserService;
import com.vit.assignmentportal.auth.CurrentUserView;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The student's dashboard: the three counts and the most recent work (FR-14).
 *
 * <h2>Why this is not a method on {@link SubmissionController}</h2>
 *
 * <p>{@code SubmissionController} is mapped at {@code /submissions} and every one of its handlers is
 * about one submission or the list of them. The dashboard lives at {@code /student/dashboard}, a
 * different part of the site, and the two would share nothing but their dependencies. A second
 * {@code @RequestMapping} class keeps the URL structure and the class structure saying the same
 * thing.
 *
 * <p>It is in the {@code submission} feature rather than a {@code dashboard} package because that is
 * what it is a view of: the student's own submissions, counted and summarised. It reads through
 * {@link SubmissionService} and holds no repository of its own.
 *
 * <h2>Authorisation is the route rule</h2>
 *
 * <p>{@code /student/**} is restricted to {@code STUDENT} in {@code SecurityConfiguration}, so no
 * handler below can run without that role — which is what makes
 * {@link CurrentUserService#requireCurrentUser()} safe to call unconditionally. There is no role
 * check here for the same reason there is none in the other controllers: a second copy of the policy
 * is a second thing to keep in step.
 */
@Controller
@RequestMapping("/student")
public class StudentDashboardController {

    /**
     * How many recent submissions the dashboard shows.
     *
     * <p>Five: enough to see what is in flight, short enough that the dashboard stays a summary. The
     * full list is one link away and is the page that pages — a pager here would be a second way to
     * browse the same rows.
     */
    private static final int RECENT_LIMIT = 5;

    private final SubmissionService submissionService;
    private final CurrentUserService currentUserService;

    public StudentDashboardController(SubmissionService submissionService,
                                      CurrentUserService currentUserService) {
        this.submissionService = submissionService;
        this.currentUserService = currentUserService;
    }

    /** The signed-in student, for the navigation fragment. A view model, never the principal. */
    @ModelAttribute("currentUser")
    public CurrentUserView currentUser() {
        return CurrentUserView.from(currentUserService.requireCurrentUser());
    }

    /** The dashboard: how much is waiting, how much is decided, and the most recent few. */
    @GetMapping("/dashboard")
    public String dashboard(Model model) {
        AuthenticatedUser student = currentUserService.requireCurrentUser();

        model.addAttribute("counts", submissionService.countsForStudent(student.id()));
        model.addAttribute("recentSubmissions",
                submissionService.listForStudent(student.id(), PageRequest.of(0, RECENT_LIMIT))
                        .map(SubmissionSummary::from));

        return "submission/dashboard";
    }
}
