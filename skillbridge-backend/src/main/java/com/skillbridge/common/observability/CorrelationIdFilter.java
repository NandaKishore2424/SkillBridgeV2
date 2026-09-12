package com.skillbridge.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Gives every request an id and puts it where the logger can reach it.
 *
 * <p>Without this, a log line says what happened and nothing about which request
 * it happened during. With twenty requests in flight the lines interleave and
 * reading them is guesswork. Every line emitted while this filter is on the
 * stack carries the same {@code traceId}, so one request's story can be pulled
 * out of the pile with a single query.
 *
 * <p><b>An inbound id is honoured</b>, which is what lets a trace span more than
 * this application: the browser, a load balancer and the AI service can all
 * quote the same id. The id is also echoed back on the response, so a user
 * reporting a problem has something concrete to quote.
 *
 * <p><b>An inbound id is also untrusted input that ends up in a log file</b>, and
 * that is the whole reason for {@link #isSafe}. A header containing a newline
 * lets an attacker append entire fabricated log entries — a convincing
 * "authentication successful" line among them. Rejecting control characters and
 * absurd lengths costs nothing and closes it; a rejected header simply means a
 * generated id instead.
 *
 * <p><b>Outside the security chain, deliberately.</b> At
 * {@code HIGHEST_PRECEDENCE} this wraps everything including authentication, so
 * a request rejected at the door still gets a correlated log line — which is
 * exactly the request you most want to be able to find. Identity is added later,
 * by {@link UserContextLogFilter}, because it does not exist yet at this point.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_TRACE = "traceId";
    public static final String MDC_USER = "userId";
    public static final String MDC_TENANT = "collegeId";
    public static final String MDC_METHOD = "httpMethod";
    public static final String MDC_PATH = "path";

    /** Long enough for a UUID or a cloud trace id, short enough not to bloat a line. */
    private static final int MAX_ID_LENGTH = 128;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String inbound = request.getHeader(HEADER);
        String correlationId = isSafe(inbound) ? inbound : UUID.randomUUID().toString();

        try {
            MDC.put(MDC_TRACE, correlationId);
            MDC.put(MDC_METHOD, request.getMethod());
            MDC.put(MDC_PATH, request.getRequestURI());

            response.setHeader(HEADER, correlationId);

            filterChain.doFilter(request, response);
        } finally {
            // Not optional. Tomcat pools its threads, so an MDC left populated is
            // inherited by whatever request lands on this thread next -- and that
            // request then logs under a previous user's id, which is worse than
            // having no identity at all because it looks authoritative.
            MDC.clear();
        }
    }

    /**
     * Whether an inbound id can be written into a log line as-is.
     *
     * <p>Printable ASCII only: no newlines, no carriage returns, no terminal
     * escape sequences. This is the log-injection guard, and it is the reason
     * the header is validated rather than merely length-checked.
     */
    static boolean isSafe(String value) {
        return value != null
                && !value.isBlank()
                && value.length() <= MAX_ID_LENGTH
                && value.chars().allMatch(c -> c >= 32 && c < 127);
    }
}
