package com.vit.assignmentportal.auth;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The landing page a user reaches after signing in.
 *
 * <p>It is small on purpose, but it is not a placeholder: it is the destination that makes the
 * login flow complete, and the first place the current-user abstraction is exercised end to end
 * — session, principal, service, view model, template. Without it, {@code defaultSuccessUrl("/")}
 * would point at a route that does not exist, and a successful sign-in would end at an error
 * page.
 *
 * <p>The route is covered by {@code anyRequest().authenticated()}, so
 * {@code requireCurrentUser()} cannot fail here. It is still called rather than
 * {@code currentUser()} so that if the route rules are ever loosened, the page fails loudly
 * instead of rendering for nobody.
 */
@Controller
public class HomeController {

    private final CurrentUserService currentUserService;

    public HomeController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /**
     * Renders the signed-in user's home page.
     *
     * <p>Only a {@link CurrentUserView} reaches the template — never the {@link User} entity. The
     * conversion happens here, at the boundary, so a template cannot reach a field the view model
     * does not expose.
     */
    @GetMapping("/")
    public String home(Model model) {
        AuthenticatedUser user = currentUserService.requireCurrentUser();
        model.addAttribute("currentUser", CurrentUserView.from(user));
        return "auth/home";
    }
}
