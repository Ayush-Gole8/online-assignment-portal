package com.vit.assignmentportal.auth;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The authentication surface: the two pages the filter chain renders when it turns a request
 * away.
 *
 * <p>There is no {@code POST /login} and no {@code POST /logout} here, and their absence is the
 * design. Spring Security's {@code UsernamePasswordAuthenticationFilter} consumes the login
 * submission, and its {@code LogoutFilter} consumes the sign-out request — both before the
 * request reaches {@code DispatcherServlet}. A controller mapping those paths would either never
 * be called or would be a second, weaker authentication path beside the tested one
 * (ADR-005).
 *
 * <p>So this class renders pages; it never sees a credential. That is why nothing here depends
 * on {@code UserService} or on the password encoder: by the time a user lands on these pages the
 * authentication decision has already been made by the framework.
 */
@Controller
public class AuthController {

    private final CurrentUserService currentUserService;

    public AuthController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /**
     * The sign-in page.
     *
     * <p>Reads two query parameters that Spring Security's filters append when they redirect
     * here:
     *
     * <ul>
     *   <li>{@code error} — present after a failed authentication attempt.</li>
     *   <li>{@code logout} — present after a successful sign-out.</li>
     * </ul>
     *
     * <p>Both are rendered as a single generic sentence. The page never distinguishes "no such
     * account" from "wrong password", because doing so would turn the sign-in form into an oracle
     * for discovering which email addresses are registered. The detail exists, but it is written
     * to the server's log by {@code DatabaseUserDetailsService}, not to the browser.
     *
     * <p>Note that these are read as {@code String}, not as {@code boolean}: a redirect to
     * {@code /login?error} produces an empty-string value rather than a boolean, and mapping it to
     * {@code boolean} would fail to bind.
     */
    @GetMapping("/login")
    public String loginPage(
            @RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "logout", required = false) String logout,
            Model model) {

        model.addAttribute("signInFailed", error != null);
        model.addAttribute("signedOut", logout != null);
        return "auth/login";
    }

    /**
     * The page shown to a signed-in user who lacks the role a route requires.
     *
     * <p>Reached by a server-side forward from Spring Security's
     * {@code AccessDeniedHandlerImpl}, configured in {@code SecurityConfiguration
     * #accessDeniedPage}. The 403 status is set by that handler before the forward, so the
     * browser and the pipeline both see a 403 even though the body is rendered by a controller.
     *
     * <p>A forward, not a redirect: a redirect would replace the 403 with a 200 and would lose
     * the fact that access was refused, which is precisely the signal a browser test asserts on.
     *
     * <p>The current user is put in the model so the page can be built on the signed-in layout —
     * the reader <em>is</em> signed in, and offering them a "Sign in" button, or no sign-out, would
     * describe their session incorrectly. It is read as an {@code Optional} rather than required:
     * the handler is reachable by a direct request as well as by the forward, and a page that
     * explains a refusal should render even if that request arrives without a principal.
     */
    @GetMapping("/access-denied")
    public String accessDenied(Model model) {
        model.addAttribute("currentUser",
                currentUserService.currentUser().map(CurrentUserView::from).orElse(null));
        return "auth/access-denied";
    }
}
