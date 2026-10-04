package com.vit.assignmentportal.auth;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The landing page a user reaches after signing in.
 *
 * <p>It is small on purpose, but it is not a placeholder: it is the destination that makes the
 * login flow complete, and the first place the current-user abstraction is exercised end to end
 * — session, principal, service, view, template. Without it, {@code defaultSuccessUrl("/")}
 * would point at a route that does not exist, and a successful sign-in would end at an error
 * page.
 *
 * <p>The page's {@code currentUser} model attribute is supplied by
 * {@code config.CurrentUserModelAdvice}, along with every other page's, rather than being added
 * here. The handler still resolves the principal strictly and discards it, which is deliberate: the
 * advice reads the principal <em>optionally</em> so that it can also serve the sign-in page, and
 * that would quietly turn this route's "there is always a user here" into an assumption nobody
 * checks. Asking for the principal by the method that throws keeps the property this class has
 * always had — if the route rules are ever loosened, the home page fails loudly rather than
 * rendering for nobody.
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
     * <p>The route is covered by {@code anyRequest().authenticated()}, so the assertion below cannot
     * fail. Only a {@code CurrentUserView} reaches the template — never the {@code User} entity —
     * and that conversion now happens once, in the advice.
     */
    @GetMapping("/")
    public String home() {
        currentUserService.requireCurrentUser();
        return "auth/home";
    }
}
