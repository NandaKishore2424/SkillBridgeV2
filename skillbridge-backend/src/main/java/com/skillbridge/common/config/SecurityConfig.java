package com.skillbridge.common.config;

import com.skillbridge.auth.filter.PasswordChangeRequiredFilter;
import com.skillbridge.auth.filter.TokenAuthenticationFilter;
import com.skillbridge.auth.security.JsonSecurityErrorHandler;
import com.skillbridge.common.observability.UserContextLogFilter;
import com.skillbridge.common.throttle.RateLimitingFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private final TokenAuthenticationFilter tokenAuthenticationFilter;
    private final PasswordChangeRequiredFilter passwordChangeRequiredFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final UserContextLogFilter userContextLogFilter;
    private final JsonSecurityErrorHandler securityErrorHandler;

    public SecurityConfig(
            TokenAuthenticationFilter tokenAuthenticationFilter,
            PasswordChangeRequiredFilter passwordChangeRequiredFilter,
            RateLimitingFilter rateLimitingFilter,
            UserContextLogFilter userContextLogFilter,
            JsonSecurityErrorHandler securityErrorHandler
    ) {
        this.tokenAuthenticationFilter = tokenAuthenticationFilter;
        this.passwordChangeRequiredFilter = passwordChangeRequiredFilter;
        this.rateLimitingFilter = rateLimitingFilter;
        this.userContextLogFilter = userContextLogFilter;
        this.securityErrorHandler = securityErrorHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            // Spring Security already sends X-Content-Type-Options, X-Frame-Options
            // and X-XSS-Protection: 0 by default. These are the ones it does not.
            .headers(headers -> headers
                // Only ever emitted over HTTPS, so this is inert in local
                // development and takes effect once the app is served over TLS.
                // A year, with subdomains, is the threshold the major preload
                // lists require.
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31_536_000))
                // This API returns JSON and never renders a page, so everything
                // is denied. It still matters: it applies to Spring's own error
                // pages, and it means a response coerced into being rendered
                // cannot pull in a script or be framed.
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; frame-ancestors 'none'; sandbox"))
                .referrerPolicy(referrer -> referrer.policy(
                        ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                // Opts out of powerful browser features wholesale. Nothing here
                // uses them, and saying so explicitly is free.
                .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                        "accelerometer=(), camera=(), geolocation=(), gyroscope=(), "
                        + "magnetometer=(), microphone=(), payment=(), usb=()")))
            // The only CORS policy in the application. This filter answers every
            // preflight and rejects a disallowed origin before any controller is
            // reached, so a controller-level @CrossOrigin could only mislead the
            // reader. CorsPolicyTest fails the build if one appears.
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .sessionManagement(session -> 
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            // 401 for "who are you?" (missing, expired or rejected token) and 403
            // for "no", both as JSON. Without this Spring falls back to
            // Http403ForbiddenEntryPoint and an expired token gets an empty 403,
            // which the SPA -- correctly -- does not treat as a cue to refresh.
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint(securityErrorHandler)
                .accessDeniedHandler(securityErrorHandler))
            .addFilterBefore(tokenAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            // After authentication, not before. Rate limiting keys by user id,
            // and before this filter runs there is no principal -- which is how
            // the original came to key on the raw token and reset every user's
            // limit on every refresh. An unauthenticated request simply has no
            // principal here and falls back to its address.
            .addFilterAfter(rateLimitingFilter, TokenAuthenticationFilter.class)
            .addFilterAfter(passwordChangeRequiredFilter, TokenAuthenticationFilter.class)
            // Also after authentication, for the same reason: it copies the
            // caller's identity into the logging context, and ahead of
            // TokenAuthenticationFilter there is no identity to copy.
            .addFilterAfter(userContextLogFilter, TokenAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/api/v1/colleges/active").permitAll() // Public endpoint for registration
                // Probes need health and info without a token. Everything else
                // under /actuator is admin-only: /actuator/metrics enumerates
                // every metric name, and http.server.requests carries one URI
                // template per route -- a complete map of the API surface, which
                // is the exact thing SWAGGER_ENABLED defaults to false to avoid
                // publishing. /actuator/prometheus dumps the values with it.
                .requestMatchers("/actuator/health", "/actuator/health/**",
                                 "/actuator/info").permitAll()
                .requestMatchers("/actuator/**").hasRole("SYSTEM_ADMIN")
                // The contract itself is public; the interactive UI is gated by
                // SWAGGER_ENABLED and simply is not mapped when that is false.
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/v1/admin/**").authenticated()
                .anyRequest().authenticated()
            );

        return http.build();
    }

    /**
     * CORS from {@code app.cors.allowed-origins} ({@code CORS_ALLOWED_ORIGINS}),
     * a comma-separated list of exact origins.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
        return corsConfigurationSourceFor(allowedOrigins);
    }

    /**
     * Fails at startup on a list that would fail every request. With
     * credentials allowed -- the refresh token is a cookie -- the CORS spec
     * forbids a wildcard origin, and Spring enforces that only when the first
     * cross-origin request arrives, as a 500.
     */
    static CorsConfigurationSource corsConfigurationSourceFor(List<String> allowedOrigins) {
        List<String> origins = allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (origins.isEmpty()) {
            throw new IllegalStateException("app.cors.allowed-origins is empty; the frontend could not call the API");
        }
        if (origins.stream().anyMatch(o -> o.contains("*"))) {
            throw new IllegalStateException("app.cors.allowed-origins must list exact origins, not a wildcard: "
                    + "credentials are allowed, and a wildcard with credentials is refused by every browser");
        }

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        // The refresh token travels as an HttpOnly cookie.
        configuration.setAllowCredentials(true);
        // Browsers cap this lower (Chromium at 2 hours); an hour is well inside.
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

