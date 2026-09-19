package com.skillbridge.testsupport;

import com.skillbridge.auth.security.AuthenticatedUser;
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

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
