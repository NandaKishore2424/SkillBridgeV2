package com.skillbridge.auth.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Stops an account that still holds its temporary password from doing anything
 * except replacing it.
 *
 * <p>{@code mustChangePassword} has been set on every bulk-provisioned account
 * since it was introduced, and {@code /api/v1/auth/first-login} exists to clear
 * it — but nothing ever read the flag. {@code /login} issued a full access token
 * regardless, and that token worked everywhere. The flag documented an intention
 * that was never enforced.
 *
 * <p>This is the enforcement. It runs after authentication, so the principal is
 * already resolved, and rejects any request from a flagged user that is not one
 * of {@link #ALWAYS_ALLOWED}.
 *
 * <p>Login itself is deliberately still allowed to succeed. The alternative —
 * refusing to issue a token at all — would leave the client with no way to
 * present the old password to {@code /first-login}, and the response already
 * carries {@code mustChangePassword: true} for the UI to react to.
 *
 * <p>The response is 403 with the code {@code PASSWORD_CHANGE_REQUIRED}, not a
 * generic 403, so a client can tell "you must change your password" apart from
 * "you lack the role" and route to the right screen.
 */
@Component
@RequiredArgsConstructor
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /**
     * Paths a flagged user may still reach.
     *
     * <p>{@code /first-login} and {@code /change-password} are the escape hatch.
     * {@code /logout} must stay open or a flagged user cannot end their session.
     * {@code /refresh} stays open so a token expiring mid-password-change does
     * not strand them. {@code /login} is unauthenticated and never reaches here.
     */
    private static final Set<String> ALWAYS_ALLOWED = Set.of(
            "/api/v1/auth/first-login",
            "/api/v1/auth/change-password",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh");

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        AuthenticatedUser user = SecurityUtils.currentUserIfPresent().orElse(null);

        if (user == null || !user.isMustChangePassword() || isAllowed(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        reject(request, response);
    }

    private boolean isAllowed(HttpServletRequest request) {
        // CORS preflight carries no credentials and must not be answered with 403.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        return ALWAYS_ALLOWED.contains(request.getRequestURI());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        ErrorResponse body = ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.FORBIDDEN.value())
                .error("PASSWORD_CHANGE_REQUIRED")
                .message("You must set a new password before using this account. "
                        + "Call POST /api/v1/auth/first-login with your temporary password.")
                .path(request.getRequestURI())
                .details(Map.of("action", "first-login"))
                .build();

        objectMapper.writeValue(response.getWriter(), body);
    }
}
