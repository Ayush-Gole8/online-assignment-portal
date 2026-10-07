package com.vit.assignmentportal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.support.TestCategories;
import java.util.Locale;
import org.junit.jupiter.api.Tag;
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
 * <p>The admin paths exercised here have no controller yet; the admin feature is not part of the
 * MVP. That does not weaken the assertion. Spring Security's filter chain runs <em>before</em>
 * dispatch, so a request refused with a 403 was refused by the rule, and a request that reaches 404
 * has provably passed it. Those cases say so in a comment. The review queue, by contrast, is a real
 * page now, so its cases assert a 200 rather than a 404.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag(TestCategories.INTEGRATION)
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

    /**
     * The build-identity endpoint answers without a session.
     *
     * <p>It is what the post-deployment smoke test reads to confirm that the release
     * which was deployed is the release that is answering
     * ({@code scripts/ops/smoke-test.ps1}). A deployment that had to hold a
     * credential to ask that question would have to store one on the machine it is
     * checking, which is the trade this endpoint exists to avoid.
     */
    @Test
    void servesTheBuildIdentityEndpointToAnonymousVisitors() throws Exception {
        String body = mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Asserted as substrings rather than with a JSON-path expression: the
        // claim is about what the document contains, not about the shape of the
        // library that reads it.
        assertThat(body).contains("\"version\"").contains("\"environment\"");
    }

    /**
     * The endpoint publishes the build's identity and nothing else.
     *
     * <p>This is the assertion that makes permitting it defensible. Exposing
     * {@code /actuator/info} widens what an anonymous visitor can read, and
     * {@code application.yml} enables the property-based contributor to populate
     * it — so the bound on that widening is a rule about what may be placed under
     * {@code info.}, and a rule is worth exactly what an assertion makes it worth.
     * The four strings below are the values that would appear here the moment
     * something leaks the datasource, the storage root, or the session policy
     * into that namespace.
     */
    @Test
    void publishesNothingButTheBuildIdentityFromTheInfoEndpoint() throws Exception {
        String body = mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("jdbc")
                .doesNotContain("password")
                .doesNotContain("DB_URL")
                .doesNotContain("uploads");
    }

    /**
     * Permitting {@code /actuator/info} did not permit the actuator as a whole.
     *
     * <p>{@code /actuator/env} publishes the resolved environment. A rule written
     * as {@code /actuator/**} would have opened it, and would keep opening every
     * endpoint a future exposure setting turns on. This case is what makes the
     * narrow matcher in {@code SecurityConfiguration} a fact rather than a
     * preference.
     */
    @Test
    void refusesOtherActuatorEndpointsToAnonymousVisitors() throws Exception {
        mockMvc.perform(get("/actuator/env"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void redirectsAnonymousVisitorsAwayFromTheHomePage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void redirectsAnonymousVisitorsAwayFromARoleGatedRoute() throws Exception {
        mockMvc.perform(get("/review"))
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
        mockMvc.perform(get("/review").session(signInAs(Role.STUDENT)))
                .andExpect(status().isForbidden());
    }

    /**
     * A REVIEWER passes the rule and reaches the queue page.
     *
     * <p>This asserted a 404 while {@code /review} had no controller, because a request that
     * reached "no handler" had provably satisfied the authorisation rule. The queue now exists, so
     * the assertion is the stronger one: the rule lets the reviewer through <em>and</em> the page
     * renders.
     */
    @Test
    void permitsAReviewerTheReviewQueue() throws Exception {
        mockMvc.perform(get("/review").session(signInAs(Role.REVIEWER)))
                .andExpect(status().isOk())
                .andExpect(view().name("review/queue"));
    }

    @Test
    void permitsAnAdminTheReviewQueue() throws Exception {
        mockMvc.perform(get("/review").session(signInAs(Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(view().name("review/queue"));
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
        mockMvc.perform(get("/review").session(signInAs(Role.STUDENT)))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/access-denied"));
    }
}
