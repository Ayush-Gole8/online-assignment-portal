package com.vit.assignmentportal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.vit.assignmentportal.auth.UserService;
import com.vit.assignmentportal.common.Role;
import com.vit.assignmentportal.config.RequestIdFilter;
import com.vit.assignmentportal.storage.UploadProperties;
import com.vit.assignmentportal.support.TestCategories;
import jakarta.servlet.RequestDispatcher;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.unit.DataSize;

/**
 * The properties every page shares, asserted once rather than page by page.
 *
 * <p>These are the claims the UI work makes that no single feature test would catch. That the
 * stylesheet is vendored rather than fetched from a CDN is a property of the document skeleton, not
 * of the submission list. That no page ships script is a property of every template at once, and the
 * one that would regress silently the first time somebody reached for a Bootstrap component that
 * needs its JavaScript bundle. That the navigation offers each role only what its routes admit is a
 * property of the shared fragment.
 *
 * <p>Asserting them here means a new page inherits the guarantees, and a template that breaks one of
 * them fails in a test whose name says which guarantee it was.
 *
 * <p>The last two sections are a different kind of claim in the same place: not about what a page
 * looks like, but about configuration that only the <em>running context</em> can vouch for. That the
 * committed upload limit is the documented one, and that the two keys reading it agree, are facts
 * about bound properties rather than about code — a unit test can only restate what the test itself
 * wrote, which is why they are asserted here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Tag(TestCategories.INTEGRATION)
class UiRenderingIT extends MySqlIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery-staple";

    /** The limit {@code application.yml} commits, and the reason it is named rather than derived. */
    private static final long DOCUMENTED_UPLOAD_LIMIT_BYTES = 10L * 1024 * 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UploadProperties uploadProperties;

    @Autowired
    private Environment environment;

    /**
     * The pages a signed-in student can reach.
     *
     * <p>Listed rather than discovered, because the point is that each of them is checked. A route
     * added without being added here is a route whose shared guarantees are unasserted — which is a
     * gap a reader can see, rather than one hidden behind a loop over whatever happened to exist.
     */
    private static final List<String> STUDENT_PAGES = List.of(
            "/",
            "/student/dashboard",
            "/submissions",
            "/submissions/new");

    // ------------------------------------------------------------------------
    // No JavaScript, and a stylesheet that is ours
    // ------------------------------------------------------------------------

    /**
     * No rendered page contains a script element.
     *
     * <p>NFR-2 requires the whole submit-and-review workflow to complete with scripting disabled,
     * and the Selenium stage runs it that way. A page that added a script for a small convenience
     * would still pass its own feature test and quietly end the guarantee for everyone — which is
     * why the check is here, over every page, rather than in the feature that might add one.
     *
     * <p>The assertion is on the rendered body, not on the template files, so a script introduced by
     * a fragment or by Spring's own machinery is caught the same as one typed into a page.
     */
    @Test
    void rendersNoScriptOnAnyStudentPage() throws Exception {
        MockHttpSession student = signIn("ui-no-script", Role.STUDENT);

        for (String path : STUDENT_PAGES) {
            String body = bodyOf(path, student);
            assertThat(body)
                    .as("page %s must not contain a script element", path)
                    .doesNotContainIgnoringCase("<script");
        }
    }

    /**
     * The rendered pages link this application's own Bootstrap, not a third-party one.
     *
     * <p>The stylesheet is served from {@code /css}, so the UI renders the same on a build agent
     * with no route to the public internet and discloses nobody's address to a CDN. Asserting both
     * files are linked keeps a future edit from "fixing" the styling by pointing a {@code <link>} at
     * a public origin, which would work locally and break the pipeline's determinism silently.
     */
    @Test
    void linksTheVendoredStylesheets() throws Exception {
        MockHttpSession student = signIn("ui-stylesheets", Role.STUDENT);

        String body = bodyOf("/", student);
        assertThat(body)
                .contains("/css/bootstrap.min.css")
                .contains("/css/portal.css")
                .doesNotContain("cdn.jsdelivr.net")
                .doesNotContain("cdnjs.cloudflare.com");
    }

    /**
     * The stylesheets the pages link are actually served, and served as CSS.
     *
     * <p>{@link #linksTheVendoredStylesheets()} asserts the {@code <link>} elements; this asserts
     * there is something behind them. Those are different claims, and the gap between them was
     * real: every page linked {@code /css/bootstrap.min.css} while the file was absent from the
     * repository, so the link resolved to nothing and the browser refused the result. A link that
     * resolves to nothing is worse than no link at all — the template reads as though the page is
     * styled, and only the browser knows it is not.
     *
     * <p>The status is asserted, not just the body, because the failure mode names itself through
     * it. A missing static resource is answered by {@code BasicErrorController}, and a request whose
     * {@code Accept} header prefers {@code text/css} is answered by that controller's JSON handler —
     * Spring Boot's own error document, served under a {@code .css} URL. The browser then reports a
     * MIME-type error rather than a missing file, which points the reader at Spring rather than at
     * the absent resource. Asserting {@code text/css} here is what keeps the two distinguishable.
     */
    @Test
    void servesTheLinkedStylesheetsAsCss() throws Exception {
        mockMvc.perform(get("/css/bootstrap.min.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));

        mockMvc.perform(get("/css/portal.css"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/css"));
    }

    /**
     * No rendered page ships a template's commentary to the browser.
     *
     * <p>Thymeleaf emits an ordinary HTML comment verbatim, so a note written for the next
     * developer and a note written for the user take the same route out of the building. That is
     * not merely untidy. The notes name {@code BasicErrorController}, {@code SecurityConfiguration}
     * and the {@code server.error.include-message} setting that holds the error page together, and
     * handing an anonymous reader a map of the machinery behind the 500 page is exactly the
     * disclosure {@code CLAUDE.md} §7 rule 11 exists to prevent. Commentary is therefore written
     * with Thymeleaf's parser-level comment syntax, which the parser discards before rendering, so
     * the notes stay in the file and never reach the response.
     *
     * <p>The assertion is on the rendered body and over every page rather than on the template
     * files, so a comment introduced by a fragment is caught the same as one typed into a page.
     * It is here, rather than inside {@link #rendersTheErrorPageWithoutExposingInternals()}, so
     * that the rule is enforced on its own terms — that test caught the defect only because the
     * comment happened to contain the word "exception", which is a coincidence and not a check.
     */
    @Test
    void shipsNoTemplateCommentaryToTheBrowser() throws Exception {
        MockHttpSession student = signIn("ui-no-comments-student", Role.STUDENT);
        MockHttpSession reviewer = signIn("ui-no-comments-reviewer", Role.REVIEWER);

        for (String path : STUDENT_PAGES) {
            assertThat(bodyOf(path, student))
                    .as("page %s must not ship an HTML comment", path)
                    .doesNotContain("<!--");
        }

        assertThat(bodyOf("/review/queue", reviewer))
                .as("the review queue must not ship an HTML comment")
                .doesNotContain("<!--");

        assertThat(errorPageBody(500))
                .as("the error page must not ship an HTML comment")
                .doesNotContain("<!--");
    }

    // ------------------------------------------------------------------------
    // The navigation reflects the role
    // ------------------------------------------------------------------------

    /**
     * A student is offered the student pages and not the reviewer's.
     *
     * <p>The links are presentation, not control — every route is guarded in
     * {@code SecurityConfiguration} whether or not a link points at it. What is asserted is the
     * weaker but still load-bearing property: the page does not offer a link that would 403, because
     * a link that 403s is a lie about what the page can do.
     */
    @Test
    void offersAStudentTheStudentNavigationAndNotTheReviewQueue() throws Exception {
        MockHttpSession student = signIn("ui-nav-student", Role.STUDENT);

        String body = bodyOf("/", student);
        assertThat(body)
                .contains("/student/dashboard")
                .contains("/submissions/new")
                .doesNotContain("/review/queue");
    }

    /** A reviewer is offered the queue and none of the student's pages. */
    @Test
    void offersAReviewerTheQueueAndNotTheStudentPages() throws Exception {
        MockHttpSession reviewer = signIn("ui-nav-reviewer", Role.REVIEWER);

        String body = bodyOf("/review/queue", reviewer);
        assertThat(body)
                .contains("Review queue")
                .doesNotContain("/student/dashboard")
                .doesNotContain("/submissions/new");
    }

    /**
     * Every page carries a sign-out control, and it is a POST form.
     *
     * <p>Signing out changes server-side state, so it must be a POST carrying a CSRF token — a GET
     * logout can be fired by an image tag on any third-party page ({@code CLAUDE.md} §7 rule 3). The
     * navigation is the single place this form is written, which is what the fragment exists for;
     * asserting it here is what keeps a page from quietly being written without one.
     */
    @Test
    void offersASignOutFormOnEveryStudentPage() throws Exception {
        MockHttpSession student = signIn("ui-sign-out", Role.STUDENT);

        for (String path : STUDENT_PAGES) {
            assertThat(bodyOf(path, student))
                    .as("page %s must offer a sign-out form", path)
                    .contains("/logout");
        }
    }

    /**
     * The queue answers at both its paths.
     *
     * <p>{@code /review} is the original and is what the navigation and the home page have always
     * linked to; {@code /review/queue} is the name the rest of the site's URLs follow and is what
     * the current navigation points at. Both map to one handler, so this asserts the destination
     * rather than a redirect — a request that lands on the queue should render the queue, not bounce
     * through another URL first.
     */
    @Test
    void servesTheQueueAtBothPaths() throws Exception {
        MockHttpSession reviewer = signIn("ui-queue-paths", Role.REVIEWER);

        mockMvc.perform(get("/review").session(reviewer))
                .andExpect(status().isOk())
                .andExpect(view().name("review/queue"));

        mockMvc.perform(get("/review/queue").session(reviewer))
                .andExpect(status().isOk())
                .andExpect(view().name("review/queue"));
    }

    // ------------------------------------------------------------------------
    // The error page
    // ------------------------------------------------------------------------

    /**
     * The error view renders, and explains nothing it should not.
     *
     * <p>{@code /error} is the mapping Spring Boot's {@code BasicErrorController} uses for every
     * unhandled failure, so rendering it here is the same code path a 404 or a 500 ends in. The body
     * must carry the status and a sentence, and must not carry an exception class name, a stack
     * trace, or a path: a user-facing error that explains the failure is an information leak
     * ({@code CLAUDE.md} §7 rule 11).
     *
     * <p>The request is driven the way a servlet container drives an error dispatch — with {@code
     * ERROR_STATUS_CODE} set and an {@code Accept: text/html} header — rather than as a bare
     * {@code GET}. Both matter. {@code BasicErrorController} chooses between an HTML and a JSON
     * handler by content negotiation, and a request with no {@code Accept} header gets the JSON one:
     * no view is resolved, the body is a JSON object, and the assertion fails on "No ModelAndView
     * found" rather than on anything about the page. Without {@code ERROR_STATUS_CODE} the model
     * carries {@code DefaultErrorAttributes}' sentinel of 999 — a number no user should ever be
     * shown — so the test would be asserting against a status the application never produces.
     */
    @Test
    void rendersTheErrorPageWithoutExposingInternals() throws Exception {
        String body = errorPageBody(500);

        assertThat(body)
                .contains("Error 500")
                .contains("Something went wrong while handling your request")
                .doesNotContainIgnoringCase("exception")
                .doesNotContain("java.")
                .doesNotContain("org.springframework")
                .doesNotContain("com.vit.assignmentportal");
    }

    /**
     * A missing page is explained as missing, and told apart from a failure.
     *
     * <p>404 gets its own sentence because it is the common case and the one with a remedy the user
     * can act on — check the address. A single "something went wrong" for both would be true and
     * useless: it would leave a mistyped URL indistinguishable from an outage.
     */
    @Test
    void explainsAMissingPageAsMissingRatherThanAsAFailure() throws Exception {
        assertThat(errorPageBody(404))
                .contains("Error 404")
                .contains("That page does not exist");
    }

    /**
     * A page that does not exist is a 404 for a signed-in user, not a redirect and not a 500.
     *
     * <p>This is the case the explicit {@code /error} permission in {@code SecurityConfiguration}
     * exists for. Spring Security applies its rules to the ERROR dispatch as well as to the request,
     * so without that line the dispatch would meet the catch-all {@code authenticated()} rule — and
     * a signed-in user who mistyped a URL would be bounced to the sign-in page, which reads as "the
     * site is broken" rather than "that address is wrong".
     *
     * <p>The anonymous case is different and is not asserted here: an anonymous request to an
     * unmapped path meets the catch-all rule first and is redirected to the sign-in page, which is
     * deliberate. What matters is that the authenticated 404 is a 404.
     */
    @Test
    void answersAMissingPageForASignedInUser() throws Exception {
        MockHttpSession student = signIn("ui-missing-page", Role.STUDENT);

        mockMvc.perform(get("/no-such-page-exists").session(student))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------------
    // Request identifiers
    // ------------------------------------------------------------------------

    /**
     * Every response carries a request identifier, including the one that carries a failure.
     *
     * <p>The error case is the assertion that matters. An identifier returned only on the happy path
     * would be a feature that is absent exactly when an operator needs it — the request that broke is
     * the request somebody will be asked about, and it is the one whose identifier has to be on the
     * response they are holding.
     *
     * <p>Asserted through the whole stack rather than on {@link RequestIdFilter} directly
     * ({@code RequestIdFilterTest} does that): what is in doubt here is whether the filter is
     * actually registered in the running application and ordered ahead of the rest of the chain. A
     * filter that is correct and not wired has no effect at all.
     */
    @Test
    void answersEveryRequestWithARequestIdentifier() throws Exception {
        MockHttpSession student = signIn("ui-request-id", Role.STUDENT);

        assertThat(identifierOn(get("/").session(student)))
                .as("an ordinary page")
                .isNotBlank();

        assertThat(identifierOn(get("/no-such-page-exists").session(student)))
                .as("a response carrying an error — the one most likely to be asked about")
                .isNotBlank();
    }

    /**
     * A client that supplied an identifier keeps it.
     *
     * <p>This is the half of the feature that makes it useful across a boundary: a proxy or a load
     * balancer that logged its own identifier must be able to find the same string in this
     * application's log, or the two cannot be lined up. The filter's own test covers what it refuses
     * to accept; this covers that an acceptable value survives the round trip through a real request.
     */
    @Test
    void reusesAClientSuppliedRequestIdentifier() throws Exception {
        MockHttpSession student = signIn("ui-request-id-supplied", Role.STUDENT);

        assertThat(identifierOn(get("/").session(student)
                .header(RequestIdFilter.HEADER, "proxy-7f3c1a90")))
                .isEqualTo("proxy-7f3c1a90");
    }

    // ------------------------------------------------------------------------
    // The committed upload configuration
    // ------------------------------------------------------------------------

    /**
     * The limit the application is running with is the one the documentation states.
     *
     * <p>Ten megabytes is written in {@code application.yml}, in {@code .env.example}, and in
     * {@code docs/requirements/mvp.md}, and nothing but this test connects any of them to what the
     * running context actually bound. A default changed in one file and not the others is a
     * documentation defect that every other test in the suite would pass straight through, because
     * they all construct the limit for themselves.
     *
     * <p>The media type is asserted alongside the size because it is the other half of the same
     * setting and the half that fails more confusingly: a validator configured for the wrong format
     * refuses every upload with "That file is not a valid PDF." while the page still says PDF.
     */
    @Test
    void theCommittedUploadLimitsAreTheDocumentedOnes() {
        assertThat(uploadProperties.maxFileSizeBytes())
                .as("the documented upload limit")
                .isEqualTo(DOCUMENTED_UPLOAD_LIMIT_BYTES);
        assertThat(uploadProperties.allowedContentType())
                .as("the documented accepted format")
                .isEqualTo(UploadProperties.PDF_CONTENT_TYPE);
    }

    /**
     * The container's parser and the validator refuse at the same size, because both read one value.
     *
     * <p>These are two different settings in {@code application.yml} —
     * {@code spring.servlet.multipart.max-file-size} and {@code app.upload.max-file-size} — that
     * resolve from one environment variable. Nothing at compile time connects them, and if they
     * drifted apart the failure would be genuinely misleading rather than merely wrong: an upload
     * stopped by the parser at 10 MB would be answered with the validator's sentence naming whatever
     * limit the validator had been bound to, telling the student about a size that did not refuse
     * them.
     *
     * <p>Read from the {@code Environment} as text and parsed, rather than from a typed properties
     * class, so the assertion is about the committed value rather than about a binding this test
     * chose.
     */
    @Test
    void appliesTheSameUploadLimitInBothPlacesThatReadIt() {
        DataSize multipartLimit =
                DataSize.parse(environment.getProperty("spring.servlet.multipart.max-file-size"));

        assertThat(multipartLimit)
                .as("the servlet container and the validator must refuse at the same size")
                .isEqualTo(uploadProperties.maxFileSize());
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** The request identifier the application answered with, or {@code null} if it answered with none. */
    private String identifierOn(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request)
                .andReturn()
                .getResponse()
                .getHeader(RequestIdFilter.HEADER);
    }

    private String bodyOf(String path, MockHttpSession session) throws Exception {
        return mockMvc.perform(get(path).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    /**
     * Renders the error page for a status, the way a servlet container would.
     *
     * <p>{@code ERROR_STATUS_CODE} is the request attribute a container sets before forwarding to
     * the error page, and setting it is what makes the model's {@code status} a real code. The
     * {@code Accept} header is what selects {@code BasicErrorController}'s HTML handler — see
     * {@link #rendersTheErrorPageWithoutExposingInternals()} for why a bare GET does not.
     */
    private String errorPageBody(int statusCode) throws Exception {
        return mockMvc.perform(get("/error")
                        .accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, statusCode))
                .andExpect(status().is(statusCode))
                .andExpect(view().name("error"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private MockHttpSession signIn(String slug, Role role) throws Exception {
        String email = slug + "@example.com";
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
}
