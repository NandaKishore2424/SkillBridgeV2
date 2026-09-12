package com.skillbridge.common.config;

import com.skillbridge.auth.filter.TokenAuthenticationFilter;
import com.skillbridge.common.throttle.RateLimitingFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the rate limiter sits in the chain, which is not a detail.
 *
 * <p>It has to run <b>after</b> authentication. Buckets are keyed by user id, and
 * before the authentication filter there is no principal to key on — only the
 * bearer token, which is exactly how the original implementation came to key on
 * the credential itself and hand every user a fresh quota on every refresh.
 *
 * <p>This is also the test that keeps the limiter honest under the {@code test}
 * profile. Limiting is switched off there, because the integration tests sign in
 * far more often than a human would and were being throttled into a six-minute
 * class and a cascade of connection-starvation errors. Switched off, the obvious
 * risk is that the filter quietly stops being wired at all and nobody notices —
 * so this asserts its presence and its position rather than its behaviour, which
 * {@code RateLimitingFilterTest} covers directly.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class SecurityFilterOrderTest {

    @Autowired private FilterChainProxy springSecurityFilterChain;

    @Test
    @DisplayName("rate limiting runs after authentication, so it can key by user")
    void rateLimitingComesAfterAuthentication() {
        List<Class<?>> order = filterClasses();

        int auth = indexOf(order, TokenAuthenticationFilter.class);
        int limit = indexOf(order, RateLimitingFilter.class);

        assertThat(auth)
                .as("the authentication filter is not in the chain at all")
                .isNotNegative();
        assertThat(limit)
                .as("""
                    The rate limiter is not in the chain. Limiting is disabled under the \
                    test profile, so nothing else here would notice it had been unwired \
                    -- which is the whole reason this assertion exists separately from \
                    the ones about its behaviour.""")
                .isNotNegative();

        assertThat(limit)
                .as("""
                    Rate limiting must come after authentication. Ahead of it there is no \
                    principal, so the only thing available to key on is the bearer token \
                    -- and keying on the token gives every user a brand-new quota every \
                    time it rotates, which is the bug this ordering fixed.""")
                .isGreaterThan(auth);
    }

    private List<Class<?>> filterClasses() {
        // The application-wide chain: the one whose matcher accepts everything.
        return springSecurityFilterChain.getFilterChains().stream()
                .map(SecurityFilterChain::getFilters)
                .max(java.util.Comparator.comparingInt(List::size))
                .orElseThrow()
                .stream()
                .<Class<?>>map(Filter::getClass)
                .toList();
    }

    private static int indexOf(List<Class<?>> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isAssignableFrom(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
