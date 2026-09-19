package com.skillbridge.common.config;

import com.skillbridge.auth.filter.PasswordChangeRequiredFilter;
import com.skillbridge.auth.filter.TokenAuthenticationFilter;
import com.skillbridge.auth.security.JsonSecurityErrorHandler;
import com.skillbridge.common.observability.UserContextLogFilter;
import com.skillbridge.common.throttle.RateLimitingFilter;
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

import java.util.Arrays;
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
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
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

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        
        // Allow frontend origin
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        
        // Allow all HTTP methods
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        
        // Allow all headers
        configuration.setAllowedHeaders(List.of("*"));
        
        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(true);
        
        // Cache preflight response for 1 hour
        configuration.setMaxAge(3600L);
        
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        
        return source;
    }
}

