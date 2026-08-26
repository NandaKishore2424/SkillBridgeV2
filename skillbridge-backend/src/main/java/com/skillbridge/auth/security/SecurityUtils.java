package com.skillbridge.auth.security;

import com.skillbridge.common.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * One place to get the current principal.
 *
 * <p>Every controller used to open with the same three lines — fetch the
 * context, fetch the authentication, cast the principal — and a raw cast throws
 * {@code ClassCastException} (a 500) when the principal is anything unexpected,
 * such as the anonymous token on a misconfigured public endpoint. Routing it
 * through here turns that into a 401, which is what it actually is.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * The authenticated caller, or a 401 if there isn't one.
     */
    public static AuthenticatedUser currentUser() {
        return requirePrincipal(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * The authenticated caller from a supplied {@link Authentication}, or a 401.
     */
    public static AuthenticatedUser requirePrincipal(Authentication authentication) {
        return principal(authentication)
                .orElseThrow(() -> new UnauthorizedException("Authentication required"));
    }

    /**
     * The authenticated caller if there is one. Use where an endpoint serves both
     * anonymous and signed-in callers.
     */
    public static Optional<AuthenticatedUser> principal(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            return Optional.empty();
        }
        return Optional.of(user);
    }

    public static Optional<AuthenticatedUser> currentUserIfPresent() {
        return principal(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * The caller's college, or a 422 if they have none.
     *
     * <p>A SYSTEM_ADMIN legitimately has no college, so any endpoint that scopes
     * by tenant has to decide what that means rather than dereferencing null.
     */
    public static Long requireCollegeId() {
        AuthenticatedUser user = currentUser();
        if (user.getCollegeId() == null) {
            throw new UnauthorizedException(
                    "This action requires an account scoped to a college.");
        }
        return user.getCollegeId();
    }
}
