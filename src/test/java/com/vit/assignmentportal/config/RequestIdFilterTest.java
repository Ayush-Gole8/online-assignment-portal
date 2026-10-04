package com.vit.assignmentportal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vit.assignmentportal.support.TestCategories;
import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The request identifier: where it comes from, what it is allowed to contain, and where it goes.
 *
 * <p>No Spring context. A filter is a function over a request and a response, and every claim below
 * is about that function — a context would add startup time and would not make the filter behave
 * differently.
 *
 * <p>The cases that matter most are the hostile ones. The value arrives from the client, ends up in
 * a log line and in a response header, and both are places where a crafted string does damage. Those
 * are asserted first, and asserted as a pattern rather than as an equality, so a future change that
 * loosened the allowlist would fail here rather than in production.
 */
@Tag(TestCategories.UNIT)
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    /**
     * The logging context belongs to the thread, and the container reuses threads.
     *
     * <p>A failed assertion must not leave an identifier behind for the next test to find, which
     * would turn one failure into two confusing ones.
     */
    @AfterEach
    void clearLoggingContext() {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @Test
    void givesEveryRequestAnIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        String identifier = response.getHeader(RequestIdFilter.HEADER);
        assertThat(identifier).isNotBlank();
        assertThat(request.getAttribute(RequestIdFilter.ATTRIBUTE)).isEqualTo(identifier);
    }

    /**
     * A caller that already has an identifier keeps it.
     *
     * <p>This is the point of accepting one at all: a proxy or a load balancer that has logged its
     * own identifier must be able to find the same string in this application's log, or correlating
     * the two — the entire reason for having an identifier — is impossible.
     */
    @Test
    void reusesAnAcceptableClientIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "7f3c1a90-abc_123.4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("7f3c1a90-abc_123.4");
    }

    /**
     * A newline in an accepted value would forge a second log entry, and in a header value would
     * split the response.
     */
    @Test
    void refusesAClientIdentifierThatCouldForgeALogLineOrSplitAHeader() throws Exception {
        String hostile = "abc\r\nWARN forged log entry";

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, hostile);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).doesNotContain("forged");
        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .matches("[A-Za-z0-9._-]{1,64}");
    }

    /** An unbounded value would let a caller fill a log file or a response header at will. */
    @Test
    void refusesAClientIdentifierLongerThanTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "a".repeat(65));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotEqualTo("a".repeat(65));
        assertThat(response.getHeader(RequestIdFilter.HEADER)).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void refusesAnEmptyClientIdentifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
    }

    /**
     * The identifier is in the logging context for the whole of the request, and gone afterwards.
     *
     * <p>Both halves matter. Without the first, the identifier would be returned to the caller and
     * appear in no log, which is the half of the feature that is actually useful. Without the
     * second, the next request this thread serves would be logged under somebody else's identifier.
     */
    @Test
    void putsTheIdentifierInTheLoggingContextAndRemovesItAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> duringTheRequest = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                duringTheRequest.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(duringTheRequest.get()).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    /**
     * A failure downstream does not leave the identifier on the thread.
     *
     * <p>The case a {@code finally} block exists for, and the one most likely to be reached in
     * production: the request that throws is precisely the request an operator will want to look up,
     * and it must be identifiable without the next request on that thread inheriting its name.
     */
    @Test
    void removesTheIdentifierFromTheLoggingContextWhenTheRequestFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("Something downstream failed.");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing))
                .isInstanceOf(IllegalStateException.class);

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isNotBlank();
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    /**
     * The response header is set before the chain runs, not after.
     *
     * <p>Which is what makes the identifier available on the response a caller most needs it for: the
     * one that carries an error. If it were set on the way out, the failure above would return its
     * exception with a response that named nothing.
     */
    @Test
    void setsTheResponseHeaderBeforeTheChainRuns() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> headerDuringTheRequest = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                headerDuringTheRequest.set(((MockHttpServletResponse) res)
                        .getHeader(RequestIdFilter.HEADER)));

        assertThat(headerDuringTheRequest.get()).isNotBlank();
    }
}
