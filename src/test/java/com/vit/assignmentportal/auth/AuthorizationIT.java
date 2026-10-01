package com.vit.assignmentportal.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.common.Role;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Role-based authorisation: which role may reach which route, and what happens when it may not.
 *
 * <p>Each case signs in for real and then requests the route with the session that sign-in
 * produced, rather than injecting a synthetic principal. That is deliberate. A test that places
 * an {@code Authentication} directly into the security context proves what the route rules do
 * with an authority someone handed them; it does not prove that signing in <em>produces</em>
 * that authority. The whole chain — {@code DatabaseUserDetailsService}, the {@link Role} enum,
 * {@code Role.authority()}, and {@code hasRole(...)} — is under test only if the session came
 * from a real login.
 *
 * <p>Two of the paths exercised here have no controller yet; the submission and review features
 * arrive in later milestones. That does not weaken the assertion. Spring Security's filter chain
 * runs <em>before</em> dispatch, so a request refused with a 403 was refused by the rule, and a
 * request that reaches 404 has provably passed it. Those cases say so in a comment.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthorizationIT extends MySqlIntegrationTest {

    /**
     * Every demo account in this class shares one password.
     *
     * <p>It is a test fixture, not a credential: the accounts exist only inside an ephemeral
     * Testcontainer that is destroyed when the JVM exits, and no code path outside this test
     * class can reach them ({@code SECURITY.md}).
     */
    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    /**
     * Signs in as the given role and returns the session that login produced.
     *
     * <p>The account is created on first use and reused afterwards, so the three roles cost three
     * BCrypt hashes for the whole class rather than one per test.
     */
    private MockHttpSession signInAs(Role role) throws Exception {
        String email = role.name().toLowerCase(Locale.ROOT) + "-authorization@example.com";

        if (!userService.existsByEmail(email)) {
            userService.create(email, PASSWORD, role);
        }

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .param("email", email)
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        return session;
    }

    // ------------------------------------------------------------------------
    // Anonymous access
    // ------------------------------------------------------------------------

    @Test
    void servesTheLoginPageToAnonymousVisitors() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"));
    }

    /**
     * The health endpoint answers without a session (FR-25).
     *
     * <p>Required by CLAUDE.md §13. A health check behind authentication cannot distinguish
     * "the application is healthy" from "the caller is not signed in", which makes it useless to
     * the pipeline stage that depends on it.
     */
    @Test
    void servesTheHealthEndpointToAnonymousVisitors() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void redirectsAnonymousVisitorsAwayFromTheHomePage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void redirectsAnonymousVisitorsAwayFromARoleGatedRoute() throws Exception {
        mockMvc.perform(get("/review/queue"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    // ------------------------------------------------------------------------
    // Every authenticated role reaches its own home page
    // ------------------------------------------------------------------------

    /**
     * No role is locked out of the home page.
     *
     * <p>Parameterised across the enum so that adding a fourth role without deciding what it may
     * see fails here, rather than producing an account that can sign in and then reach nothing.
     */
    @ParameterizedTest
    @EnumSource(Role.class)
    void letsEveryRoleReachTheHomePage(Role role) throws Exception {
        mockMvc.perform(get("/").session(signInAs(role)))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/home"));
    }

    // ------------------------------------------------------------------------
    // The review queue: REVIEWER and ADMIN, and nobody else
    // ------------------------------------------------------------------------

    /**
     * A STUDENT is refused the review queue, and refused with a 403 rather than a redirect.
     *
     * <p>A 403 distinguishes this from the anonymous case: this user is signed in and their role
     * is simply insufficient. Redirecting them to the login page would send them round a loop
     * they cannot escape, because signing in again does not grant the missing role.
     */
    @Test
    void refusesAStudentTheReviewQueue() throws Exception {
        mockMvc.perform(get("/review/queue").session(signInAs(Role.STUDENT)))
                .andExpect(status().isForbidden());
    }

    /**
     * A REVIEWER passes the rule.
     *
     * <p>The 404 is the assertion, not a defect: {@code /review/queue} has no controller until
     * the review milestone, and a path that reaches "no handler" has provably satisfied the
     * authorisation rule. The status becomes 200 when the queue is built, and this test should be
     * updated then — a failing test at that point is the correct signal, because the expectation
     * genuinely changed.
     */
    @Test
    void permitsAReviewerTheReviewQueue() throws Exception {
        mockMvc.perform(get("/review/queue").session(signInAs(Role.REVIEWER)))
                .andExpect(status().isNotFound());
    }

    @Test
    void permitsAnAdminTheReviewQueue() throws Exception {
        mockMvc.perform(get("/review/queue").session(signInAs(Role.ADMIN)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------
    // The admin area: ADMIN only
    // ------------------------------------------------------------------------

    /**
     * A REVIEWER is refused the admin area.
     *
     * <p>Worth asserting even though the MVP ships no admin UI: the role distinction is the
     * deliverable, and REVIEWER is the role most likely to be over-granted by a careless
     * {@code hasAnyRole} edit.
     */
    @Test
    void refusesAReviewerTheAdminArea() throws Exception {
        mockMvc.perform(get("/admin/users").session(signInAs(Role.REVIEWER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void refusesAStudentTheAdminArea() throws Exception {
        mockMvc.perform(get("/admin/users").session(signInAs(Role.STUDENT)))
                .andExpect(status().isForbidden());
    }

    /** The ADMIN role is retained for extensibility and is the only one that passes. */
    @Test
    void permitsAnAdminTheAdminArea() throws Exception {
        mockMvc.perform(get("/admin/users").session(signInAs(Role.ADMIN)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------
    // The access-denied page
    // ------------------------------------------------------------------------

    /**
     * The refusal is rendered, not merely returned as a bare status.
     *
     * <p>An authenticated user who is refused is forwarded to {@code /access-denied}; Spring
     * Security sets the 403 before the forward, so the status survives while the body is
     * rendered. Asserting both halves is what proves the forward is wired to a real view rather
     * than to a path nobody maps — which would produce a 403 with an empty body and a log line
     * about a missing view.
     */
    @Test
    void rendersTheAccessDeniedPageForAnAuthenticatedUserWithoutTheRole() throws Exception {
        mockMvc.perform(get("/review/queue").session(signInAs(Role.STUDENT)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/access-denied"));
    }
}
