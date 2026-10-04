package com.vit.assignmentportal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.support.TestCategories;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * "Who is signed in right now?" — including the answer that is easiest to get wrong.
 *
 * <p>{@link CurrentUserService} is the one place permitted to read {@code SecurityContextHolder}, so
 * it is the one place where the decision "is this request authenticated?" is made on behalf of every
 * feature. The trap it exists to avoid is stated in its own Javadoc and asserted here: Spring
 * Security represents an anonymous request as an {@code Authentication} that reports
 * {@code isAuthenticated() == true}, so a service that trusted that flag alone would hand a
 * {@code STUDENT} role to everyone who reached the login page.
 *
 * <p>No Spring context. The service reads a thread-local, and a test that loads a container to
 * populate one would be slower without being any more true; the wiring — that a real request's
 * principal is really this object — is what {@code AuthenticationIT} and the route tests prove.
 */
@Tag(TestCategories.UNIT)
class CurrentUserServiceTest {

    private static final AuthenticatedUser STUDENT = new AuthenticatedUser(
            UUID.fromString("11111111-1111-1111-1111-111111111111"),
            "student@example.com",
            "$2a$10$notarealhashbutshapedlikeone",
            Role.STUDENT,
            true);

    private final CurrentUserService service = new CurrentUserService();

    /**
     * The security context is a thread-local shared by the whole JVM, so an authentication set by one
     * test would be visible to the next one on the same thread. Clearing it afterwards is what keeps
     * these tests independent of their order.
     */
    @AfterEach
    void clearTheSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    /** Nothing has authenticated: the ordinary case for a login page, a health check, or a favicon. */
    @Test
    void reportsEmptyWhenNothingHasAuthenticated() {
        SecurityContextHolder.clearContext();

        assertThat(service.currentUser()).isEmpty();
    }

    /**
     * The anonymous user is not a signed-in user, despite reporting itself as authenticated.
     *
     * <p>This is the case the type check exists for. Asserting only that the principal is the string
     * {@code "anonymousUser"} would be asserting the framework's representation; the property under
     * test is that the service refuses to treat it as an identity.
     */
    @Test
    void refusesToTreatTheAnonymousUserAsSignedIn() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "anonymous-key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(service.currentUser()).isEmpty();
    }

    /** A real principal is returned unchanged. */
    @Test
    void returnsTheAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(STUDENT, STUDENT.getPassword(),
                        STUDENT.getAuthorities()));

        assertThat(service.currentUser()).contains(STUDENT);
    }

    /**
     * {@link CurrentUserService#requireCurrentUser()} fails loudly when nothing is signed in.
     *
     * <p>It is called only from routes the security configuration already requires to be
     * authenticated, so its absence is a defect in this application rather than a condition a user
     * can cause. Failing loudly is the point: returning empty would let a caller quietly proceed
     * without a principal.
     */
    @Test
    void requireCurrentUserFailsWhenNobodyIsSignedIn() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(service::requireCurrentUser)
                .isInstanceOf(IllegalStateException.class);
    }

    /** Under an authenticated request, {@code requireCurrentUser()} returns the principal itself. */
    @Test
    void requireCurrentUserReturnsThePrincipalWhenThereIsOne() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(STUDENT, STUDENT.getPassword(),
                        STUDENT.getAuthorities()));

        assertThat(service.requireCurrentUser()).isSameAs(STUDENT);
    }
}
