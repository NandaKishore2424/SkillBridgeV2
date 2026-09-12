package com.skillbridge.common.throttle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.security.AuthenticatedUser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter, and the three ways the previous one could be stepped around.
 *
 * <p>No Spring context and no database, so these run on every build rather than
 * only when {@code DATABASE_URL} is set. A limiter whose guard only runs in the
 * gated profile is a guard that will not run on the day it is needed.
 */
class RateLimitingFilterTest {

    private SimpleMeterRegistry meters;
    private ObjectMapper mapper;
    private RateLimitTierResolver tiers;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        mapper = new ObjectMapper();
        tiers = new RateLimitTierResolver();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private RateLimitingFilter filter(int trustedProxies) {
        return new RateLimitingFilter(new RateLimitKeyResolver(trustedProxies), tiers, meters, mapper, true);
    }

    private static void authenticateAs(long userId) {
        Role role = new Role();
        role.setName("STUDENT");
        User user = User.builder().id(userId).email("u" + userId + "@example.invalid")
                .collegeId(1L).isActive(true).roles(Set.of(role)).build();
        AuthenticatedUser principal = new AuthenticatedUser(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("10.0.0.1");
        return request;
    }

    private static MockHttpServletRequest login(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.setContentType("application/json");
        request.setContent(("{\"email\":\"" + email + "\",\"password\":\"x\"}")
                .getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private int statusAfter(RateLimitingFilter filter, MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Nested
    @DisplayName("keying")
    class Keying {

        @Test
        @DisplayName("the limit follows the user, so a token refresh does not reset it")
        void refreshDoesNotResetTheLimit() throws Exception {
            RateLimitingFilter filter = filter(0);
            authenticateAs(42L);

            // Spend the MUTATION burst. Same user throughout, but each request
            // carries a different bearer token -- which is exactly what a
            // fifteen-minute refresh cycle looks like, and what used to hand the
            // user a brand-new bucket.
            int rejected = 0;
            for (int i = 0; i < 40; i++) {
                MockHttpServletRequest request =
                        new MockHttpServletRequest("POST", "/api/v1/student/batches/apply");
                request.setRemoteAddr("10.0.0.1");
                request.addHeader("Authorization", "Bearer token-number-" + i);
                if (statusAfter(filter, request) == 429) {
                    rejected++;
                }
            }

            assertThat(rejected)
                    .as("""
                        MUTATION allows 30 a minute. Keyed on the token -- as this was \
                        until 2026-09-10 -- all forty would pass, because every request \
                        brought a new key and therefore a full bucket.""")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("two users do not share a bucket")
        void usersAreIndependent() throws Exception {
            RateLimitingFilter filter = filter(0);

            authenticateAs(1L);
            for (int i = 0; i < 30; i++) {
                statusAfter(filter, new MockHttpServletRequest("POST", "/api/v1/x") {{
                    setRemoteAddr("10.0.0.1");
                }});
            }

            authenticateAs(2L);
            MockHttpServletRequest second = new MockHttpServletRequest("POST", "/api/v1/x");
            second.setRemoteAddr("10.0.0.1");

            assertThat(statusAfter(filter, second))
                    .as("one user exhausting their quota must not lock out everybody else")
                    .isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("X-Forwarded-For")
    class ForwardedFor {

        @Test
        @DisplayName("a forged header does not buy a fresh bucket")
        void forgedHeaderIsIgnoredWithNoProxies() throws Exception {
            RateLimitingFilter filter = filter(0);

            int rejected = 0;
            for (int i = 0; i < 30; i++) {
                MockHttpServletRequest request = get("/api/v1/colleges/active");
                // The attack: a different claimed origin on every request.
                request.addHeader("X-Forwarded-For", "1.2.3." + i);
                if (statusAfter(filter, request) == 429) {
                    rejected++;
                }
            }

            assertThat(rejected)
                    .as("""
                        PUBLIC allows 20 a minute from one address. Reading this header \
                        from the left -- as the previous filter did -- gave each forged \
                        value its own bucket, so all thirty passed and the limiter was \
                        decorative.""")
                    .isEqualTo(10);
        }

        @Test
        @DisplayName("with one trusted proxy the rightmost hop is the client")
        void countsBackFromTheRight() {
            RateLimitKeyResolver resolver = new RateLimitKeyResolver(1);
            MockHttpServletRequest request = get("/api/v1/x");
            // The client seeded the left of this chain; our proxy appended 9.9.9.9.
            request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 9.9.9.9");

            assertThat(resolver.clientIp(request))
                    .as("taking hops[0] is the vulnerability; the trustworthy entry is the "
                            + "one our own proxy added, at the right")
                    .isEqualTo("9.9.9.9");
        }

        @Test
        @DisplayName("with no proxies in front the header is not consulted at all")
        void headerIgnoredWhenNothingAppendsToIt() {
            RateLimitKeyResolver resolver = new RateLimitKeyResolver(0);
            MockHttpServletRequest request = get("/api/v1/x");
            request.addHeader("X-Forwarded-For", "1.2.3.4");

            assertThat(resolver.clientIp(request))
                    .as("nothing of ours appends to this header today, so none of it is evidence")
                    .isEqualTo("10.0.0.1");
        }
    }

    @Nested
    @DisplayName("tiers")
    class Tiers {

        @Test
        @DisplayName("login is charged to the email as well as the address")
        void loginIsChargedToBothKeys() throws Exception {
            RateLimitingFilter filter = filter(0);

            // Five attempts against one account exhaust AUTHENTICATION.
            for (int i = 0; i < 5; i++) {
                statusAfter(filter, login("victim@example.invalid"));
            }

            MockHttpServletRequest sixth = login("victim@example.invalid");
            sixth.setRemoteAddr("10.0.0.99");   // a different address entirely

            assertThat(statusAfter(filter, sixth))
                    .as("""
                        One account attacked from many addresses is invisible to a \
                        per-address limit. Charging the attempt to the email as well is \
                        what catches it -- and the address key still catches one address \
                        spraying many accounts.""")
                    .isEqualTo(429);
        }

        @Test
        @DisplayName("the login body still reaches the controller after being read")
        void bodyIsReplayed() throws Exception {
            RateLimitingFilter filter = filter(0);
            MockHttpServletRequest request = login("someone@example.invalid");
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            String seen = new String(
                    ((jakarta.servlet.http.HttpServletRequest) chain.getRequest())
                            .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(seen)
                    .as("a servlet body is a one-shot stream; reading it in a filter without "
                            + "replaying it leaves the controller with nothing to parse")
                    .contains("someone@example.invalid");
        }

        @Test
        @DisplayName("reads, writes, logins and uploads land in different tiers")
        void pathsResolveToTheRightTier() {
            assertThat(tiers.resolve(new MockHttpServletRequest("POST", "/api/v1/auth/login")))
                    .isEqualTo(RateLimitTier.AUTHENTICATION);
            assertThat(tiers.resolve(new MockHttpServletRequest("POST", "/api/v1/auth/refresh")))
                    .as("refresh needs a valid refresh token, and five a minute would break "
                            + "a user with several tabs open")
                    .isEqualTo(RateLimitTier.PUBLIC);
            assertThat(tiers.resolve(new MockHttpServletRequest("GET", "/api/v1/student/batches")))
                    .isEqualTo(RateLimitTier.STANDARD);
            assertThat(tiers.resolve(new MockHttpServletRequest("PUT", "/api/v1/trainer/topics/1/progress")))
                    .isEqualTo(RateLimitTier.MUTATION);
            assertThat(tiers.resolve(new MockHttpServletRequest("POST", "/api/v1/admin/students/bulk-upload")))
                    .isEqualTo(RateLimitTier.EXPENSIVE);
        }
    }

    @Nested
    @DisplayName("response contract")
    class ResponseContract {

        @Test
        @DisplayName("every response says where the client stands, and a 429 says for how long")
        void headersAreAlwaysPresent() throws Exception {
            RateLimitingFilter filter = filter(0);

            MockHttpServletResponse allowed = new MockHttpServletResponse();
            filter.doFilter(get("/api/v1/colleges/active"), allowed, new MockFilterChain());

            assertThat(allowed.getHeader("X-RateLimit-Limit")).isEqualTo("20");
            assertThat(allowed.getHeader("X-RateLimit-Remaining"))
                    .as("a client told nothing until it is rejected cannot back off first")
                    .isEqualTo("19");

            MockHttpServletResponse rejected = null;
            for (int i = 0; i < 25; i++) {
                rejected = new MockHttpServletResponse();
                filter.doFilter(get("/api/v1/colleges/active"), rejected, new MockFilterChain());
            }

            assertThat(rejected.getStatus()).isEqualTo(429);
            assertThat(rejected.getHeader("Retry-After"))
                    .as("without this the client can only guess, and guessing means retrying "
                            + "immediately")
                    .isNotNull();
            assertThat(rejected.getContentAsString()).contains("RATE_LIMITED", "retryAfterSeconds");
        }

        @Test
        @DisplayName("rejections are counted, per tier")
        void rejectionsAreMetered() throws Exception {
            RateLimitingFilter filter = filter(0);
            for (int i = 0; i < 25; i++) {
                statusAfter(filter, get("/api/v1/colleges/active"));
            }

            assertThat(meters.find("rate_limit.rejected").tag("tier", "PUBLIC").counter())
                    .as("a limiter nobody can see rejecting traffic is indistinguishable "
                            + "from an application that is simply broken")
                    .isNotNull()
                    .extracting(c -> c.count())
                    .isEqualTo(5.0d);
        }
    }

    @Nested
    @DisplayName("failure")
    class Failure {

        @Test
        @DisplayName("a broken limiter lets traffic through rather than becoming an outage")
        void failsOpen() throws Exception {
            RateLimitKeyResolver exploding = new RateLimitKeyResolver(0) {
                @Override
                public List<String> resolve(jakarta.servlet.http.HttpServletRequest request,
                                            RateLimitTier tier, String email) {
                    throw new IllegalStateException("bucket store unavailable");
                }
            };
            RateLimitingFilter filter =
                    new RateLimitingFilter(exploding, tiers, meters, mapper, true);

            assertThat(statusAfter(filter, get("/api/v1/student/batches")))
                    .as("""
                        Fail open. A limiter that rejects everything when its own \
                        machinery breaks has turned a small internal fault into a total \
                        outage of everything it was protecting.""")
                    .isEqualTo(200);

            assertThat(meters.find("rate_limit.failures").counter())
                    .as("failing open silently is how you discover months later that the "
                            + "limiter has not run since March")
                    .isNotNull()
                    .extracting(c -> c.count())
                    .isEqualTo(1.0d);
        }
    }
}
