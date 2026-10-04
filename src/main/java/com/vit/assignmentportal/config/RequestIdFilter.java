package com.vit.assignmentportal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an identifier, puts it in the logging context, and returns it to the caller.
 *
 * <h2>What it is for</h2>
 *
 * <p>A user who hits a 500 can quote one short string and an operator can find the one log line that
 * describes what happened to <em>their</em> request. Without it, a support conversation is bounded
 * by a timestamp to the second and a guess about which of a hundred concurrent requests was theirs.
 * That is the whole ambition: an identifier, in the logs and in a header. There is no span, no
 * collector, and no tracing backend, and none is planned at this stage.
 *
 * <h2>The client's value is not trusted</h2>
 *
 * <p>An {@code X-Request-ID} sent by the client is used when it is acceptable and discarded
 * otherwise. This is a security decision, not a formatting one. The value ends up in a log line and
 * in a response header, and both are places where a crafted string causes harm: a newline in a log
 * message forges a second entry, and a newline in a header value splits the response. The
 * {@link #ACCEPTABLE} pattern is an <strong>allowlist</strong> — letters, digits, dot, underscore and
 * hyphen, at most {@value #MAX_LENGTH} characters — which makes both unrepresentable rather than
 * filtered out afterwards. Anything else, including an empty value, is replaced with a generated
 * identifier; a caller is never told that its value was rejected, because there is nothing for it to
 * do about it and echoing the decision would describe the check to somebody probing it.
 *
 * <p>Reusing the client's value at all is worth the small risk: a reverse proxy, a load balancer, or
 * a caller that has already logged its own identifier would otherwise see a different one in this
 * application's logs, and correlating the two is the point of having one.
 *
 * <h2>Why {@code HIGHEST_PRECEDENCE}</h2>
 *
 * <p>The filter has to run before Spring Security's chain, so that a request the security filters
 * turn away — a missing CSRF token, a failed sign-in, a route the caller may not reach — is still
 * identified in the log. Spring Security's chain has a well-known order near the front of the
 * filter list, so this sits in front of it. The identifier is also cleared in a {@code finally}
 * block: the container reuses threads, and an identifier left in the thread's MDC would be attached
 * to the next, unrelated request that thread serves.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    /** The request and response header carrying the identifier. */
    public static final String HEADER = "X-Request-ID";

    /** The logging-context key the pattern in {@code application.yml} reads. */
    public static final String MDC_KEY = "requestId";

    /**
     * The request attribute carrying the identifier, for code that wants it rather than the log.
     */
    public static final String ATTRIBUTE = "portalRequestId";

    /** Bounded so that an accepted value cannot be used to fill a log file or a header. */
    private static final int MAX_LENGTH = 64;

    /**
     * The shape an accepted caller-supplied identifier must have. Everything outside this set is
     * refused, which is what keeps control characters out of the log and the response.
     */
    private static final Pattern ACCEPTABLE =
            Pattern.compile("[A-Za-z0-9._-]{1," + MAX_LENGTH + "}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String identifier = acceptable(request.getHeader(HEADER))
                ? request.getHeader(HEADER)
                : UUID.randomUUID().toString();

        request.setAttribute(ATTRIBUTE, identifier);
        // Set on the response before the chain runs, so the header is present even when something
        // downstream throws — which is precisely the response a caller most wants to quote.
        response.setHeader(HEADER, identifier);
        MDC.put(MDC_KEY, identifier);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static boolean acceptable(String candidate) {
        return candidate != null && ACCEPTABLE.matcher(candidate).matches();
    }
}
