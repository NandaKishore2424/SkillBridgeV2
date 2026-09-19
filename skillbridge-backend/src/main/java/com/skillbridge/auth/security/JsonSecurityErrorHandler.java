package com.skillbridge.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * What the security filter chain answers when it refuses a request, in the same
 * {@link ErrorResponse} shape {@code GlobalExceptionHandler} uses everywhere else.
 *
 * <p><b>Why this exists.</b> Until 2026-09-19 no entry point was configured, so
 * Spring Security fell back to {@code Http403ForbiddenEntryPoint}: a missing,
 * malformed or <em>expired</em> token got <b>403 with an empty body</b>.
 * Measured on 2026-09-17. That broke the SPA. It refreshes its access token on
 * 401, the status that means "who are you?", and treats 403, "I know who you
 * are, and no", as final. So every session died about fifteen minutes after
 * login (one access-token lifetime) until the page was reloaded.
 *
 * <p>The two cases, per RFC 7235 / RFC 6750:
 * <ul>
 *   <li><b>401 + {@code WWW-Authenticate: Bearer}</b>: no usable credentials.
 *       When a token was presented and rejected (expired, tampered, revoked),
 *       the header also carries {@code error="invalid_token"}, which is the
 *       client's cue to refresh.</li>
 *   <li><b>403</b>: authenticated, but a URL rule refuses the caller (for
 *       example a college admin on {@code /actuator/metrics}). Denials raised
 *       by {@code @PreAuthorize} inside a controller never reach here;
 *       {@code GlobalExceptionHandler} answers those in the same shape.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class JsonSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    /**
     * Set by {@code TokenAuthenticationFilter} when a bearer token was presented
     * but not accepted, so the 401 can say {@code invalid_token} rather than
     * just "no credentials".
     */
    public static final String REJECTED_TOKEN_ATTRIBUTE = JsonSecurityErrorHandler.class.getName() + ".rejectedToken";

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        boolean tokenRejected = request.getAttribute(REJECTED_TOKEN_ATTRIBUTE) != null;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                tokenRejected ? "Bearer error=\"invalid_token\"" : "Bearer");
        write(request, response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                tokenRejected ? "The access token is invalid or has expired" : "Authentication required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(code)
                .message(message)
                .path(request.getRequestURI())
                .build());
    }
}
