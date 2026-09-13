package com.skillbridge.testsupport;

import com.skillbridge.auth.security.AuthenticatedUser;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Set;

/**
 * Support for {@code @WebMvcTest} controller slices.
 *
 * <h2>What this deliberately does and does not load</h2>
 *
 * <p>It enables <b>method security</b>, because {@code @PreAuthorize} on the
 * controller methods is most of what a slice test is here to check, and without
 * {@code @EnableMethodSecurity} those annotations are inert — every
 * authorisation test would pass while proving the opposite of what it claims.
 *
 * <p>It does <b>not</b> import {@code SecurityConfig}. That would drag in four
 * filters and their dependencies (a user repository, the JWT service, the
 * revocation service, a Caffeine cache, a meter registry) into a slice whose
 * point is to be small. The filter chain is already covered where it belongs:
 * {@code SecurityFilterOrderTest} asserts the ordering against a real context,
 * {@code TokenRevocationTest} the revocation, {@code RateLimitingFilterTest} the
 * limiter. Duplicating that here would be slower and no more true.
 *
 * <p>The division is worth stating plainly, because getting it wrong is how a
 * suite ends up slow and still full of holes:
 *
 * <ul>
 *   <li><b>Slice</b> — does this endpoint return the right status, enforce the
 *       right role, reject the wrong body, and hand the service the right
 *       arguments?</li>
 *   <li><b>Integration</b> — does the filter chain assemble, does the token
 *       decode, does the tenant filter apply, does the SQL do what it says?</li>
 * </ul>
 */
@TestConfiguration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class WebSliceSupport {

    /**
     * Everything is permitted at the filter level, so a 403 in a slice test can
     * only have come from {@code @PreAuthorize}.
     *
     * <p>If URL rules were enforced here too, a test asserting 403 would pass
     * whether or not the method annotation existed, which is precisely the
     * assertion it is supposed to be making.
     */
    @Bean
    SecurityFilterChain sliceFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .build();
    }

    /** Authenticates the request as a caller holding {@code roles}. */
    public static RequestPostProcessor as(long userId, Long collegeId, String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, "slice-" + userId + "@example.invalid", collegeId,
                true, false, Set.of(roles));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
    }

    /** A trainer, the role that may grade. */
    public static RequestPostProcessor trainer(long userId) {
        return as(userId, 1L, "TRAINER");
    }

    /** A student: may read their own progress, may not grade. */
    public static RequestPostProcessor student(long userId) {
        return as(userId, 1L, "STUDENT");
    }

    /** A college admin: may read a batch overview, may not grade. */
    public static RequestPostProcessor collegeAdmin(long userId) {
        return as(userId, 1L, "COLLEGE_ADMIN");
    }
}
