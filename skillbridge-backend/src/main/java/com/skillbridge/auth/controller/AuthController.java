package com.skillbridge.auth.controller;

import com.skillbridge.auth.AuthProperties;

import com.skillbridge.auth.dto.AuthResponse;
import com.skillbridge.auth.dto.ChangePasswordRequest;
import com.skillbridge.auth.dto.CurrentUserDTO;
import com.skillbridge.auth.dto.FirstLoginRequest;
import com.skillbridge.auth.dto.LoginRequest;
import com.skillbridge.auth.dto.LogoutRequest;
import com.skillbridge.auth.dto.RefreshTokenRequest;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.auth.service.AuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Login, session renewal and password changes.
 *
 * <p>Every endpoint that starts or renews a session answers with the access
 * token in the body and the refresh token <b>only</b> in the
 * {@value #REFRESH_COOKIE_NAME} cookie: HttpOnly, so script cannot read it;
 * SameSite=Lax; path-scoped to {@code /api/v1/auth}, so no other request carries
 * it; {@code Secure} unless {@code app.auth.refresh-cookie-secure} is false,
 * which only plain-http local development needs.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Slf4j
public class AuthController {

    static final String REFRESH_COOKIE_NAME = "skillbridge_refresh_token";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final boolean secureCookie;

    public AuthController(AuthService authService, AuthProperties auth) {
        this.authService = authService;
        this.secureCookie = auth.refreshCookieSecure();
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return withSession(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@RequestBody(required = false) RefreshTokenRequest request,
                                                     HttpServletRequest httpRequest) {
        String token = request == null ? null : request.getRefreshToken();
        if (token == null || token.isBlank()) {
            token = readRefreshTokenFromCookie(httpRequest);
        }
        return withSession(authService.refreshToken(token));
    }

    /**
     * The authenticated caller. How the SPA rehydrates after a reload: the token
     * says who you are, but not your name or whether your profile is complete.
     *
     * <p>Takes the principal through {@link SecurityUtils} rather than
     * {@code @AuthenticationPrincipal User}: the security context holds an
     * {@link AuthenticatedUser}, so that parameter was always null and every call
     * threw NullPointerException.
     */
    @GetMapping("/me")
    public ResponseEntity<CurrentUserDTO> me() {
        return ResponseEntity.ok(authService.describeCurrentUser(SecurityUtils.currentUser()));
    }

    /**
     * Ends every session of this user and starts a new one for the caller, whose
     * old access token stops working with the rest.
     */
    @PostMapping("/change-password")
    public ResponseEntity<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        AuthenticatedUser user = SecurityUtils.currentUser();
        return withSession(authService.changePassword(user.getId(), request.getOldPassword(), request.getNewPassword()));
    }

    @PostMapping("/first-login")
    public ResponseEntity<AuthResponse> firstLogin(@Valid @RequestBody FirstLoginRequest request) {
        return withSession(authService.firstLogin(request.getEmail(), request.getTemporaryPassword(),
                request.getNewPassword()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestBody(required = false) LogoutRequest request,
                                       HttpServletRequest httpRequest) {
        String token = request == null ? null : request.getRefreshToken();
        if (token == null || token.isBlank()) {
            token = readRefreshTokenFromCookie(httpRequest);
        }
        authService.logout(token);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie("", 0).toString())
                .build();
    }

    private ResponseEntity<AuthResponse> withSession(AuthResponse response) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(response.getRefreshToken(), authService.refreshTokenTtlSeconds()).toString())
                .body(response);
    }

    /** The cookie lives exactly as long as the token behind it: both come from jwt.refreshTokenTtlSeconds. */
    private ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE_NAME, value)
                .httpOnly(true)
                .secure(secureCookie)
                .path(COOKIE_PATH)
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();
    }

    private static String readRefreshTokenFromCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (REFRESH_COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
