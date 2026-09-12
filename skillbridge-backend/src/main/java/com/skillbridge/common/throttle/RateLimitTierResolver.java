package com.skillbridge.common.throttle;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Which tier a request belongs to, decided from its path and method.
 *
 * <p>Path prefixes rather than annotations, deliberately. An annotation has to
 * be remembered on every new endpoint and its absence is silent — a new upload
 * endpoint would quietly get the read limit. A prefix rule covers endpoints
 * nobody has written yet, and the default for anything unrecognised is the
 * stricter of the two plausible answers.
 */
@Component
public class RateLimitTierResolver {

    public RateLimitTier resolve(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();

        // Brute-force targets. /auth/refresh is deliberately not here: it needs a
        // valid refresh token to do anything, and at five a minute a user with
        // several tabs open would start failing to stay signed in.
        if (path.startsWith("/api/v1/auth/login")
                || path.startsWith("/api/v1/auth/first-login")
                || path.startsWith("/api/v1/auth/change-password")
                || path.startsWith("/api/v1/auth/forgot-password")
                || path.startsWith("/api/v1/auth/reset-password")) {
            return RateLimitTier.AUTHENTICATION;
        }

        // Fans out into the database a row at a time, or into the AI service.
        if (path.contains("/bulk-upload") || path.contains("/upload")
                || path.contains("/progress/backfill") || path.contains("/syllabus/copy-from")) {
            return RateLimitTier.EXPENSIVE;
        }

        // Unauthenticated: the registration form's college list, and refresh.
        if (path.startsWith("/api/v1/colleges/active")
                || path.startsWith("/api/v1/auth/refresh")) {
            return RateLimitTier.PUBLIC;
        }

        return switch (method) {
            case "POST", "PUT", "PATCH", "DELETE" -> RateLimitTier.MUTATION;
            default -> RateLimitTier.STANDARD;
        };
    }
}
