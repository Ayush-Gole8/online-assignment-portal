package com.vit.assignmentportal.config;

import com.vit.assignmentportal.auth.CurrentUserService;
import com.vit.assignmentportal.auth.CurrentUserView;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the signed-in user in the model of every page, once.
 *
 * <h2>What it replaces</h2>
 *
 * <p>Three controllers had grown the same method — an identical
 * {@code @ModelAttribute("currentUser")} returning {@link CurrentUserView#from} of
 * {@link CurrentUserService#requireCurrentUser()} — and two more set the attribute inline. Every one
 * of them existed to feed the same navigation fragment, so the fragment's requirement was written
 * five times and would have had to be remembered a sixth time by whoever added the next controller.
 * A page that forgot it would render a navbar with no sign-out control, which is exactly the kind of
 * silent breakage {@code UiRenderingIT} exists to catch — and it is better not to be able to write
 * it than to be caught writing it.
 *
 * <h2>Why the value may be absent</h2>
 *
 * <p>This method uses {@link CurrentUserService#currentUser()} — the optional one — and puts
 * {@code null} in the model for an anonymous request. It has to: a {@code @ControllerAdvice} runs
 * for <em>every</em> controller, including the sign-in page and the error page, and
 * {@code requireCurrentUser()} throws when there is no principal. Calling it here would break the
 * two pages that must render without a session.
 *
 * <p>Nothing is lost by that. A handler that needs a principal still calls
 * {@code requireCurrentUser()} itself and still fails loudly if the route rules are ever loosened;
 * this class only decides what the navigation bar has to render. The fragment already tolerates an
 * absent {@code currentUser} — that is what lets the error page render for a signed-in user whose
 * session the error dispatch does not carry.
 */
@ControllerAdvice
public class CurrentUserModelAdvice {

    /** The model attribute name every template and fragment already uses. */
    public static final String CURRENT_USER_ATTRIBUTE = "currentUser";

    private final CurrentUserService currentUserService;

    public CurrentUserModelAdvice(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /**
     * The view model for the page header, or {@code null} when the request is anonymous.
     *
     * <p>A view model, never the {@code AuthenticatedUser} principal: a template that can reach a
     * field can render it, and the principal carries a credential hash. See
     * {@link CurrentUserView}.
     */
    @ModelAttribute(CURRENT_USER_ATTRIBUTE)
    public CurrentUserView currentUser() {
        return currentUserService.currentUser().map(CurrentUserView::from).orElse(null);
    }
}
