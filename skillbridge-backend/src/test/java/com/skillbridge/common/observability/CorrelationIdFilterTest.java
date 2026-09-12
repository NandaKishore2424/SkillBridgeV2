package com.skillbridge.common.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Correlation ids, and the two ways this filter could quietly do harm.
 *
 * <p>No Spring context and no database, so these run on every build.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static MockHttpServletRequest request() {
        return new MockHttpServletRequest("GET", "/api/v1/student/batches");
    }

    /** Captures what the MDC looked like while the request was in flight. */
    private static FilterChain capturing(java.util.Map<String, String> into) {
        return (req, res) -> {
            java.util.Map<String, String> snapshot = MDC.getCopyOfContextMap();
            if (snapshot != null) {
                into.putAll(snapshot);
            }
        };
    }

    @Nested
    @DisplayName("the id")
    class TheId {

        @Test
        @DisplayName("an id is generated when none arrives, and echoed back")
        void generatesAndEchoes() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            var seen = new java.util.HashMap<String, String>();

            filter.doFilter(request(), response, capturing(seen));

            assertThat(seen.get(CorrelationIdFilter.MDC_TRACE))
                    .as("every request needs an id, including the ones nobody sent one for")
                    .isNotBlank();
            assertThat(response.getHeader(CorrelationIdFilter.HEADER))
                    .as("echoed so a user reporting a problem has something to quote")
                    .isEqualTo(seen.get(CorrelationIdFilter.MDC_TRACE));
        }

        @Test
        @DisplayName("an inbound id is honoured, so a trace spans services")
        void honoursInboundId() throws Exception {
            MockHttpServletRequest request = request();
            request.addHeader(CorrelationIdFilter.HEADER, "frontend-abc-123");
            var seen = new java.util.HashMap<String, String>();

            filter.doFilter(request, new MockHttpServletResponse(), capturing(seen));

            assertThat(seen.get(CorrelationIdFilter.MDC_TRACE))
                    .as("generating a fresh id here would break the trace at our door")
                    .isEqualTo("frontend-abc-123");
        }

        @Test
        @DisplayName("the method and path travel with the id")
        void recordsMethodAndPath() throws Exception {
            var seen = new java.util.HashMap<String, String>();

            filter.doFilter(request(), new MockHttpServletResponse(), capturing(seen));

            assertThat(seen.get(CorrelationIdFilter.MDC_METHOD)).isEqualTo("GET");
            assertThat(seen.get(CorrelationIdFilter.MDC_PATH)).isEqualTo("/api/v1/student/batches");
        }
    }

    @Nested
    @DisplayName("log injection")
    class LogInjection {

        @Test
        @DisplayName("a header with a newline in it is refused")
        void refusesForgedLogLines() throws Exception {
            MockHttpServletRequest request = request();
            // The attack: everything after the newline becomes its own log line,
            // and it can be made to look exactly like a real one.
            request.addHeader(CorrelationIdFilter.HEADER,
                    "abc\nWARN  [x] c.s.auth.AuthService - Authentication successful for admin");
            var seen = new java.util.HashMap<String, String>();

            filter.doFilter(request, new MockHttpServletResponse(), capturing(seen));

            assertThat(seen.get(CorrelationIdFilter.MDC_TRACE))
                    .as("""
                        An attacker who can put a newline into a value that reaches a log \
                        file can forge whole entries, including a convincing \
                        "authentication successful". The header is untrusted input and \
                        gets validated, not merely length-checked.""")
                    .doesNotContain("Authentication successful")
                    .doesNotContain("\\n");
        }

        @Test
        @DisplayName("control characters, absurd lengths and blanks are all refused")
        void refusesTheRest() {
            assertThat(CorrelationIdFilter.isSafe("a".repeat(129)))
                    .as("an unbounded id bloats every line of the request it belongs to")
                    .isFalse();
            assertThat(CorrelationIdFilter.isSafe("abc[31m"))
                    .as("terminal escapes can rewrite what a reader sees")
                    .isFalse();
            assertThat(CorrelationIdFilter.isSafe("  ")).isFalse();
            assertThat(CorrelationIdFilter.isSafe(null)).isFalse();
            assertThat(CorrelationIdFilter.isSafe("9f8c-4b21-trace")).isTrue();
        }
    }

    @Nested
    @DisplayName("cleanup")
    class Cleanup {

        @Test
        @DisplayName("the context is cleared, so the next request on this thread is not mislabelled")
        void clearsAfterwards() throws Exception {
            filter.doFilter(request(), new MockHttpServletResponse(), new MockFilterChain());

            assertThat(MDC.getCopyOfContextMap())
                    .as("""
                        Tomcat pools threads. An MDC left populated is inherited by the \
                        next request to land here, which then logs under the previous \
                        user's identity -- worse than no identity, because it looks \
                        authoritative.""")
                    .isNullOrEmpty();
        }

        @Test
        @DisplayName("and cleared even when the request blows up")
        void clearsOnException() {
            FilterChain exploding = (req, res) -> {
                throw new ServletException("handler failed");
            };

            assertThatThrownBy(() ->
                    filter.doFilter(request(), new MockHttpServletResponse(), exploding))
                    .isInstanceOf(ServletException.class);

            assertThat(MDC.getCopyOfContextMap())
                    .as("a failing request is the one most likely to be retried onto this "
                            + "thread's next occupant; clearing must not depend on success")
                    .isNullOrEmpty();
        }
    }
}
