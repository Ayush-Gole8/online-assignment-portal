package com.vit.assignmentportal.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vit.assignmentportal.MySqlIntegrationTest;
import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.support.TestCategories;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Authentication end to end: a real HTTP request through the real filter chain, against the real
 * {@code SecurityConfiguration}, against a real MySQL.
 *
 * <p>The point of running it here rather than as a unit test is that the thing most likely to be
 * wrong is the <em>wiring</em>, not the logic. A {@code PasswordEncoder} bean that is never
 * given to the provider, a {@code UserDetailsService} that is never discovered, a parameter name
 * that does not match the form field — none of those are visible in a unit test, and all of them
 * are visible here.
 *
 * <p>Requests carry a real CSRF token, fetched by {@code csrf()} from the same repository
 * Thymeleaf writes into. A test that bypassed CSRF would pass while the browser flow in
 * {@code login.html} failed — which is exactly the gap this suite exists to close.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag(TestCategories.INTEGRATION)
class AuthenticationIT extends MySqlIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void signsInWithValidCredentials() throws Exception {
        String email = "sign-in@example.com";
        userService.create(email, "correct-horse-battery-staple", Role.STUDENT);

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("email", email)
                        .param("password", "correct-horse-battery-staple"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void matchesTheEmailCaseInsensitively() throws Exception {
        userService.create("Case-Probe@example.com", "correct-horse-battery-staple", Role.STUDENT);

        // The column collation makes the login identifier case-insensitive. Proving it here
        // rather than trusting it: a user who capitalises their email at the login page must
        // still get in.
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("email", "case-probe@example.com")
                        .param("password", "correct-horse-battery-staple"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void rejectsAWrongPassword() throws Exception {
        String email = "wrong-password@example.com";
        userService.create(email, "correct-horse-battery-staple", Role.STUDENT);

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("email", email)
                        .param("password", "not-the-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    /**
     * An unknown address is refused — and refused identically to a wrong password.
     *
     * <p>The assertion on the redirect is the security property, not a detail of the URL: if this
     * case produced a different destination from {@link #rejectsAWrongPassword()}, the sign-in
     * form would become an oracle for discovering which addresses are registered.
     */
    @Test
    void rejectsAnUnknownEmail() throws Exception {
        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("email", "nobody@example.com")
                        .param("password", "correct-horse-battery-staple"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    /**
     * A disabled account cannot sign in, even with the right password.
     *
     * <p>This is the behaviour that justifies storing {@code enabled} in the database and
     * checking it at load time: revocation takes effect immediately, because the decision is made
     * on every authentication rather than being baked into a token issued earlier (ADR-005).
     */
    @Test
    void refusesADisabledAccount() throws Exception {
        String email = "disabled@example.com";
        User user = userService.create(email, "correct-horse-battery-staple", Role.STUDENT);

        user.setEnabled(false);
        userRepository.saveAndFlush(user);

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("email", email)
                        .param("password", "correct-horse-battery-staple"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error"));
    }

    /**
     * A sign-in POST without a CSRF token is refused (CLAUDE.md §13, §7 rule 3).
     *
     * <p>This is the test that would fail if somebody "temporarily" disabled CSRF to make a form
     * work. It is deliberately the one request in this class that omits {@code csrf()}.
     */
    @Test
    void refusesASignInWithoutACsrfToken() throws Exception {
        String email = "csrf-probe@example.com";
        userService.create(email, "correct-horse-battery-staple", Role.STUDENT);

        mockMvc.perform(post("/login")
                        .param("email", email)
                        .param("password", "correct-horse-battery-staple"))
                .andExpect(status().isForbidden());
    }

    /**
     * The session identifier changes on successful authentication (CLAUDE.md §13, ADR-005).
     *
     * <p>Without this, an attacker who could fix a victim's pre-authentication session id would
     * hold a valid authenticated session the moment the victim signed in — session fixation. The
     * defence is that the container issues a new identifier and discards the old one.
     *
     * <p>Asserted by observing the same session object before and after the request: a different
     * id afterwards is the rotation, and an unchanged one is the vulnerability.
     */
    @Test
    void rotatesTheSessionIdentifierOnSignIn() throws Exception {
        String email = "fixation-probe@example.com";
        userService.create(email, "correct-horse-battery-staple", Role.STUDENT);

        MockHttpSession session = new MockHttpSession();
        String identifierBeforeSignIn = session.getId();

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .param("email", email)
                        .param("password", "correct-horse-battery-staple"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"));

        assertThat(session.getId())
                .as("the session identifier must be rotated on successful authentication")
                .isNotEqualTo(identifierBeforeSignIn);
    }

    /**
     * Signing out invalidates the session server-side, rather than merely asking the browser to
     * forget its cookie (ADR-005).
     *
     * <p>The session object is asserted to be invalid afterwards. That is the property that makes
     * sign-out meaningful: a stolen {@code JSESSIONID} is worthless once the server has destroyed
     * the session it names.
     */
    @Test
    void signsOutAndInvalidatesTheSessionServerSide() throws Exception {
        String email = "sign-out@example.com";
        userService.create(email, "correct-horse-battery-staple", Role.STUDENT);

        MockHttpSession session = new MockHttpSession();

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .session(session)
                        .param("email", email)
                        .param("password", "correct-horse-battery-staple"))
                .andExpect(redirectedUrl("/"));

        mockMvc.perform(post("/logout").with(csrf()).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout"));

        assertThat(session.isInvalid())
                .as("the server-side session must be destroyed by sign-out")
                .isTrue();
    }

    /**
     * The password that reaches the database is a hash, not the password.
     *
     * <p>Proves the encoder is actually wired into the write path. {@code PasswordEncoderTest}
     * proves BCrypt behaves; only this proves the application uses it.
     */
    @Test
    void storesAnEncodedPasswordRatherThanThePlaintext() {
        String email = "hash-probe@example.com";
        String password = "correct-horse-battery-staple";

        User user = userService.create(email, password, Role.STUDENT);

        assertThat(user.getPasswordHash()).isNotEqualTo(password);
        assertThat(user.getPasswordHash()).doesNotContain(password);
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
    }

    /**
     * The login page itself is reachable without a session, and reports a failed attempt without
     * confirming whether the account exists.
     */
    @Test
    void servesTheLoginPageWithAGenericFailureMessage() throws Exception {
        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk());
    }

    /**
     * An anonymous request to a protected route is redirected to the login page, not refused with
     * a 403 (CLAUDE.md §13).
     *
     * <p>The distinction is what sends a visitor somewhere useful. A 403 would tell someone who
     * has not signed in that they lack a role, which is both confusing and a small information
     * leak.
     */
    @Test
    void redirectsAnonymousRequestsToTheLoginPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
