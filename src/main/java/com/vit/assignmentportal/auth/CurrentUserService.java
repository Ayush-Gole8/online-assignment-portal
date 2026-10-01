package com.vit.assignmentportal.auth;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * Answers "who is signed in right now?", which is the question every feature eventually asks.
 *
 * <p>It exists so that no other class has to. A feature service that read
 * {@code SecurityContextHolder} itself — or, worse, a controller that accepted a user id as a
 * request parameter — would be enforcing authorisation from a value the caller supplied. Here
 * the principal comes from the server-side security context, which was populated by the session
 * at authentication time ({@code CLAUDE.md} §7 rule 8).
 *
 * <p>This class is the one place permitted to touch {@code SecurityContextHolder} directly;
 * everything else asks it.
 *
 * <p>See docs/architecture/application-structure.md §5 — "Who is signed in right now?" →
 * {@code auth/CurrentUserService}.
 */
@Service
public class CurrentUserService {

    /**
     * The signed-in user, or empty when the request is anonymous.
     *
     * <p>Empty is the ordinary answer, not an error: the request may be for the login page, for
     * the health endpoint, or for a static asset, none of which has a principal.
     *
     * <p>Spring Security represents an anonymous request as an {@code Authentication} whose
     * principal is the string {@code "anonymousUser"} and whose {@code isAuthenticated()} is
     * {@code true}. The type check below is therefore the real test — checking
     * {@code isAuthenticated()} alone would report the anonymous user as signed in.
     */
    public Optional<AuthenticatedUser> currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof AuthenticatedUser user
                ? Optional.of(user)
                : Optional.empty();
    }

    /**
     * The signed-in user, or an exception.
     *
     * <p>For code that runs only behind a route requiring authentication, where the absence of a
     * principal means the security configuration is wrong. That is a defect in this application,
     * not a condition a user can cause, so it fails loudly instead of returning empty and letting
     * a caller quietly proceed unauthenticated.
     *
     * @throws IllegalStateException if no authenticated user is present
     */
    public AuthenticatedUser requireCurrentUser() {
        return currentUser().orElseThrow(() -> new IllegalStateException(
                "No authenticated user is present in the security context. This method may only "
                        + "be called from a request that passed an authenticated route rule."));
    }
}
