package com.skillbridge.common.config;

import com.skillbridge.auth.filter.PasswordChangeRequiredFilter;
import com.skillbridge.auth.filter.TokenAuthenticationFilter;
import com.skillbridge.common.observability.UserContextLogFilter;
import com.skillbridge.common.throttle.RateLimitingFilter;
import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Keeps the security-chain filters out of the servlet chain.
 *
 * <p>Spring Boot registers <em>every</em> {@code Filter} bean into the servlet
 * container automatically. The filters below are also added to Spring Security's
 * chain by {@link SecurityConfig}, so each was registered twice — and the only
 * thing preventing them from running twice per request was that they all happen
 * to extend {@code OncePerRequestFilter}, whose already-filtered attribute makes
 * the second invocation a no-op.
 *
 * <p>That is a real dependency on an implementation detail, and it hides
 * something worse: <b>the order declared in {@code SecurityConfig} is only the
 * effective order because the security chain registers at {@code -100} and a
 * plain filter bean defaults to {@code LOWEST_PRECEDENCE}</b>. One
 * {@code @Order(HIGHEST_PRECEDENCE)} on any of these would move it ahead of
 * authentication and nothing would fail — the rate limiter would quietly go back
 * to having no principal to key on, which is the exact bug fixed on 2026-09-10.
 *
 * <p>Disabling auto-registration removes both problems. Each of these filters now
 * exists in exactly one chain, in exactly the position {@code SecurityConfig}
 * states.
 *
 * <p><b>Only filters that {@code SecurityConfig} actually adds to the chain
 * belong here.</b> Two are deliberately absent, and the distinction cost a test
 * failure to learn:
 *
 * <ul>
 *   <li>{@code CorrelationIdFilter} <em>should</em> be a servlet filter, because
 *       it has to wrap the security chain so that a request rejected during
 *       authentication still produces a correlated log line.</li>
 *   <li>{@code IdempotencyKeyFilter} is never added to the security chain at
 *       all. It carries {@code @Order(HIGHEST_PRECEDENCE + 20)} and reads no
 *       principal, so running ahead of security is its design. Listing it here
 *       removed it from the application outright: idempotency stopped being
 *       enforced, a replayed request answered 200 where it should have answered
 *       422, and nothing else noticed. {@code IdempotencyContractTest} caught
 *       it.</li>
 * </ul>
 *
 * <p>That failure is also the proof of the hazard described above.
 * {@code IdempotencyKeyFilter} is a living example of an {@code @Order}
 * annotation placing a filter bean ahead of the security chain, which is exactly
 * what would silently undo the rate limiter's position.
 */
@Configuration
public class FilterRegistrationConfig {

    @Bean
    public FilterRegistrationBean<TokenAuthenticationFilter> tokenAuthenticationFilterRegistration(
            TokenAuthenticationFilter filter) {
        return notInTheServletChain(filter);
    }

    @Bean
    public FilterRegistrationBean<RateLimitingFilter> rateLimitingFilterRegistration(
            RateLimitingFilter filter) {
        return notInTheServletChain(filter);
    }

    @Bean
    public FilterRegistrationBean<PasswordChangeRequiredFilter> passwordChangeFilterRegistration(
            PasswordChangeRequiredFilter filter) {
        return notInTheServletChain(filter);
    }

    @Bean
    public FilterRegistrationBean<UserContextLogFilter> userContextLogFilterRegistration(
            UserContextLogFilter filter) {
        return notInTheServletChain(filter);
    }

    private static <T extends Filter> FilterRegistrationBean<T> notInTheServletChain(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
