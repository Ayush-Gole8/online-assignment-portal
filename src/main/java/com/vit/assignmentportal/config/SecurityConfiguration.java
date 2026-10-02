package com.vit.assignmentportal.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The application's security policy, expressed as route rules, form-login wiring, and a session
 * policy — the configuration ADR-005 mandates.
 *
 * <p>This class lives in {@code config} rather than in {@code auth} because it is the outermost
 * ring of the dependency graph: it may depend on every feature, and no feature may depend on it
 * (docs/architecture/application-structure.md §4 rule 3). {@code auth} supplies the
 * {@code UserDetailsService} and the encoder's consumer; this class supplies the beans and the
 * policy that connect them to the servlet container.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>There is no {@code .csrf(csrf -> csrf.disable())}, no {@code .sessionManagement(...)} that
 * turns off fixation protection, and no {@code permitAll()} on the application's own routes.
 * Every one of these defaults is load-bearing and each is asserted by an integration test, so
 * that a change to them fails the build rather than shipping quietly
 * ({@code CLAUDE.md} §7 rule 3).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfiguration {

    /**
     * The single filter chain that governs every request.
     *
     * <p>Spring Security's {@code HttpSecurity} is not a stateless description — it is a builder
     * that assembles the filter chain in the order the methods are called, and the resulting
     * chain is what actually runs. The order below reads as the request lifecycle, and it is the
     * order the pipeline's Selenium stage exercises through a real browser.
     *
     * <p><strong>Note what this class does not do: verify a password.</strong> The
     * {@code DaoAuthenticationProvider} that Boot's autoconfiguration assembles from the
     * {@code UserDetailsService} bean and the {@link #passwordEncoder()} bean does that, using a
     * constant-time comparison. Hand-writing it here would be the mistake ADR-005 warns about.
     */
    @Bean
    SecurityFilterChain portalSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // ------------------------------------------------------------------
                // 1. Authorisation — first match wins, so the order of these lines IS
                //    the policy. Public routes are named one by one; nothing is granted
                //    by a wildcard that could later cover a route it should not.
                // ------------------------------------------------------------------
                .authorizeHttpRequests(authorize -> authorize

                        // The login page must be reachable by someone who is not signed in;
                        // that is the entire point of it.
                        //
                        // Static assets are public because a stylesheet is not sensitive, and
                        // because the login page cannot render without one. The build output
                        // contains no script that reads or writes application state.
                        .requestMatchers("/login", "/css/**", "/js/**", "/images/**",
                                "/favicon.ico").permitAll()

                        // The health endpoint is the delivery pipeline's health-check stage
                        // (FR-25). It must answer without a session — a check that requires
                        // authentication cannot distinguish "healthy" from "unreachable", and
                        // it reports status only, with no details (application.yml).
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()

                        // Error dispatch. Spring Security 6 and later applies these rules to
                        // ERROR dispatch as well as REQUEST, so without this line an anonymous
                        // visitor who mistypes a URL is redirected to the login page instead of
                        // seeing a 404 — which reads as "the site is broken" rather than "that
                        // page does not exist".
                        .requestMatchers("/error").permitAll()

                        // ------------------------------------------------------------------
                        // 2. Role-gated routes.
                        //
                        //    The review queue is real now; /admin/** still has no controller,
                        //    because no admin UI ships in the MVP. The admin rule is declared
                        //    anyway because the authorisation model is a deliverable in its own
                        //    right, and AuthorizationIT proves a STUDENT is refused there while
                        //    an ADMIN is not.
                        //
                        //    /review and /review/** are listed separately rather than relying on
                        //    `/review/**` to also cover the bare path: whether a trailing `**`
                        //    matches the empty remainder is a matcher-implementation detail, and
                        //    the queue lives at exactly `/review`. Stating both removes the
                        //    question — and a rule that silently failed to cover its own page
                        //    would fall through to `anyRequest().authenticated()`, which is the
                        //    one outcome a role rule must never have.
                        //
                        //    hasAnyRole/hasRole prepend ROLE_ themselves, matching
                        //    Role.authority().
                        // ------------------------------------------------------------------
                        .requestMatchers("/review", "/review/**").hasAnyRole("REVIEWER", "ADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // ------------------------------------------------------------------
                        // 3. Everything else requires authentication.
                        //
                        //    A default of "deny unless authenticated" rather than "permit
                        //    unless stated" means a route added by a future milestone is
                        //    protected the moment it exists, without anyone remembering to
                        //    add it here. Forgetting to secure a new page is the failure this
                        //    line exists to prevent.
                        // ------------------------------------------------------------------
                        .anyRequest().authenticated())

                // ----------------------------------------------------------------------
                // 4. Form login (ADR-005). GET /login renders the page; POST /login is handled
                //    by Spring Security's UsernamePasswordAuthenticationFilter, not by a
                //    controller — which is why AuthController has no POST mapping.
                // ----------------------------------------------------------------------
                .formLogin(form -> form
                        .loginPage("/login")

                        // The form field is named `email` rather than Spring Security's default
                        // `username`, so that the whole stack — the column, the entity, the
                        // record, the template, and the field a browser submits — uses one word
                        // for one concept.
                        .usernameParameter("email")

                        // No alwaysUse: a user who was bounced to the login page from a deep
                        // link is returned to the page they asked for, not dumped on the home
                        // page.
                        .defaultSuccessUrl("/")

                        // The failure and success URLs stay at their defaults (/login?error and
                        // the destination above). The page reads the `error` parameter to show
                        // one generic message — it never says whether the email exists.
                        .permitAll())

                // ----------------------------------------------------------------------
                // 5. Logout. ADR-005 requires the session to be invalidated server-side, so
                //    the session object is destroyed rather than merely dereferenced by the
                //    browser discarding its cookie.
                // ----------------------------------------------------------------------
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")

                        // Destroys the server-side session. Without this the HttpSession would
                        // remain valid and a stolen JSESSIONID would still authenticate.
                        .invalidateHttpSession(true)

                        // Clears the SecurityContext too, so the authenticated principal is not
                        // left attached to a request that outlives the logout.
                        .clearAuthentication(true)

                        // Tells the browser to drop the cookie as well. Belt and braces: the
                        // session is already dead server-side, but a stale cookie would be
                        // presented on the next request and silently create a new session.
                        .deleteCookies("JSESSIONID")

                        // With CSRF enabled — see below — this must be a POST. A GET logout can
                        // be triggered by an <img> tag on any site the user visits, which is
                        // exactly the class of attack CSRF protection exists to stop.
                        .permitAll())

                // ----------------------------------------------------------------------
                // 6. Session policy. Session fixation protection is on by default; it is
                //    stated here because ADR-005 names it as a requirement, and a requirement
                //    that exists only as a framework default is a requirement nobody can see
                //    in a review.
                //
                //    What it does: on successful authentication the container issues a NEW
                //    session identifier (changeSessionId) and the old one is discarded. Without
                //    it, an attacker who can fix a victim's pre-authentication session id would
                //    hold a valid authenticated session the moment the victim signs in.
                //
                //    Session timeout and cookie attributes are NOT set here — they are
                //    configuration, because they differ between local and deployed
                //    environments (server.servlet.session.* in application.yml, ADR-005
                //    follow-up).
                // ----------------------------------------------------------------------
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId()))

                // ----------------------------------------------------------------------
                // 7. Access denied. An authenticated user who lacks the required role is
                //    forwarded to a 403 page. This is distinct from the unauthenticated case:
                //    an anonymous user never reaches this handler — they are redirected to the
                //    login page by the authentication entry point instead.
                //
                //    The distinction matters for the user. "Sign in" and "you are signed in,
                //    but not allowed" are different problems with different remedies, and
                //    collapsing them into one page sends a signed-in user round a login loop.
                // ----------------------------------------------------------------------
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedPage("/access-denied"))

                // ----------------------------------------------------------------------
                // 8. CSRF protection stays ENABLED — stated explicitly so that the decision is
                //    visible here rather than implicit in a framework default.
                //
                //    It may not be disabled without an ADR containing a threat analysis
                //    (CLAUDE.md §7 rule 3). Thymeleaf injects the token into every form written
                //    with th:action, and the login and logout forms below rely on that.
                // ----------------------------------------------------------------------
                .csrf(Customizer.withDefaults());

        return http.build();
    }

    /**
     * The password encoder: BCrypt, with the framework's default strength.
     *
     * <p>BCrypt is chosen for three properties that matter here. It is <strong>adaptive</strong>
     * — the cost factor can be raised as hardware improves, so a stored hash does not become
     * cheap to attack as the years pass. It is <strong>salted per hash</strong>, so two users
     * with the same password do not share a hash and a rainbow table is useless. And it is
     * <strong>deliberately slow</strong>, which is the only real defence against offline
     * brute-forcing of a stolen table.
     *
     * <p>The default strength is 10. Raising it makes each sign-in more expensive for the
     * attacker and for the server in equal measure; changing it is a deliberate operational
     * trade-off, not a default to be tweaked by reflex.
     *
     * <p>Declared as a bean so that {@code UserService} receives it by type. There is no other
     * encoder in the application, and no component may construct a {@code BCryptPasswordEncoder}
     * for itself — one encoder, one policy.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
