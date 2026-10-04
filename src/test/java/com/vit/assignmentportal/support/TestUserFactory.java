package com.vit.assignmentportal.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vit.assignmentportal.auth.AuthenticatedUser;
import com.vit.assignmentportal.auth.DatabaseUserDetailsService;
import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import java.util.Locale;
import java.util.UUID;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Creates the users an integration test needs, through the same services the application uses.
 *
 * <h2>Why a fixture and not a hand-built principal</h2>
 *
 * <p>Half the integration tests in this project need "a signed-in student" or "a signed-in
 * reviewer", and before this class each one carried its own copy of the same fifteen lines: create
 * the account if it is not there, load it as a principal, post the credentials to {@code /login}
 * with a CSRF token, and keep the session. Copies drift, and a copy that quietly stopped signing in
 * for real — by placing a principal in the security context instead — would still pass while testing
 * nothing about the security configuration.
 *
 * <h2>Real accounts, real sign-in</h2>
 *
 * <p>{@link #principalFor} builds the principal by calling
 * {@link DatabaseUserDetailsService#loadUserByUsername}, which is what Spring Security itself calls
 * during authentication. The identifier on the returned principal therefore names a row that exists,
 * so a submission written against it satisfies its foreign key — rather than failing later for a
 * reason unrelated to what the test is about.
 *
 * <p>{@link #signIn} goes one step further and drives {@code POST /login} through MockMvc with a real
 * CSRF token, so the session it returns came from the application's own filter chain. That is what
 * makes the authorisation tests tests of the <em>configuration</em> and not of a test harness.
 *
 * <h2>The password is a throwaway, not a secret</h2>
 *
 * <p>{@link #PASSWORD} is a literal because there is nothing to protect: it is the credential for
 * disposable accounts on a container this suite creates and discards ({@code CLAUDE.md} §7 rule 1 is
 * about real secrets, and a password that exists only inside a test JVM is not one). Every account
 * is still created through {@link UserService}, so the value is BCrypt-hashed before it is stored —
 * the hash path is part of what the sign-in tests exercise.
 */
public final class TestUserFactory {

    /** The password every fixture account is created with. Disposable; see the class note. */
    public static final String PASSWORD = "correct-horse-battery-staple";

    private final UserService userService;
    private final DatabaseUserDetailsService userDetailsService;

    /**
     * @param userService         the application's own account service, used so every fixture account
     *                            is created — and its password encoded — exactly as a real one is
     * @param userDetailsService  the adapter Spring Security authenticates against, used so the
     *                            principal a fixture returns is the one the application would
     *                            produce for that account
     */
    public TestUserFactory(UserService userService, DatabaseUserDetailsService userDetailsService) {
        this.userService = userService;
        this.userDetailsService = userDetailsService;
    }

    /**
     * The deterministic email for a {@code slug} and {@code role}.
     *
     * <p>Derived rather than supplied so that {@link #idFor}, {@link #principalFor} and
     * {@link #signIn} all address the same account without a caller threading an email between them.
     * The role is part of it so that one slug can stand for two users — a student and the reviewer
     * who decides their work — which is what a two-account test needs.
     */
    public String emailFor(String slug, Role role) {
        return "%s-%s@example.com".formatted(slug, role.name().toLowerCase(Locale.ROOT));
    }

    /**
     * The identifier of the account for a {@code slug} and {@code role}, creating it if it does not
     * yet exist.
     *
     * <p>Idempotent, so a test may call it freely: the suite shares one database, and treating "the
     * account is already there" as an error would make a second test in the same class fail for a
     * reason that has nothing to do with the behaviour under test.
     */
    public UUID idFor(String slug, Role role) {
        ensureAccount(slug, role);
        return userService.findByEmail(emailFor(slug, role)).orElseThrow().getId();
    }

    /**
     * The authenticated principal for a {@code slug} and {@code role}, creating the account if it
     * does not yet exist.
     *
     * <p>For service-level integration tests that call a service directly and need the principal such
     * a call would have received from {@code CurrentUserService}. A web test should use
     * {@link #signIn} instead, so that the principal it works with is one the filter chain produced.
     */
    public AuthenticatedUser principalFor(String slug, Role role) {
        ensureAccount(slug, role);
        return (AuthenticatedUser) userDetailsService.loadUserByUsername(emailFor(slug, role));
    }

    /**
     * Signs in as the account for a {@code slug} and {@code role}, creating it if it does not exist,
     * and returns the authenticated session.
     *
     * <p>The session comes from a real {@code POST /login} carrying a CSRF token, so it is proof that
     * the account authenticated rather than an assertion that it did. A failure to sign in throws
     * rather than returning a session that is not really authenticated, because a test that fails at
     * this step should say so here and not at a later assertion that happens to notice.
     *
     * @param mockMvc the configured MockMvc to drive the request through
     * @return the session, ready to be passed to further MockMvc requests
     */
    public MockHttpSession signIn(MockMvc mockMvc, String slug, Role role) throws Exception {
        ensureAccount(slug, role);

        MockHttpSession session = new MockHttpSession();
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .param("email", emailFor(slug, role))
                        .param("password", PASSWORD))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        return session;
    }

    /** Creates the account for a {@code slug} and {@code role} unless it is already registered. */
    private void ensureAccount(String slug, Role role) {
        String email = emailFor(slug, role);
        if (!userService.existsByEmail(email)) {
            userService.create(email, PASSWORD, role);
        }
    }
}
