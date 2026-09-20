package com.skillbridge.testsupport;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.service.JwtService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/**
 * Puts a caller in the security context for tests that call services directly.
 *
 * <p>Services decide tenancy from the caller (TenantGuard reads the security
 * context), so a service test with no caller can no longer answer "whose
 * college?". Tests must say who is asking, exactly as the HTTP layer would.
 */
public final class TestAuthentication {

    private TestAuthentication() {
    }

    /** Authenticates as the given user, scoped to {@code collegeId}, with {@code roles}. */
    public static void as(Long userId, Long collegeId, String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(
                userId, "user" + userId + "@test.invalid", collegeId, true, false, Set.of(roles));
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /**
     * An {@code Authorization} header value for {@code user} acting in one role.
     *
     * <p>Eight test classes wrote this themselves. Each has to agree with the
     * token the application issues -- the claims authentication reads since
     * 2026-09-10 -- and eight copies is eight places to forget a claim when it
     * changes.
     */
    public static String bearer(JwtService jwtService, User user, String role) {
        return "Bearer " + token(jwtService, user, role);
    }

    /** The raw token, for a test that takes it apart. */
    public static String token(JwtService jwtService, User user, String role) {
        return jwtService.generateAccessToken(user, role, Set.of(role), false);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
