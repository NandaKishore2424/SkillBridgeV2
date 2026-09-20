package com.skillbridge.auth;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code app.auth.*}.
 *
 * @param refreshCookieSecure the refresh cookie's Secure flag. True everywhere but local
 *                            development over http://localhost, where a Secure cookie is
 *                            not stored at all
 * @param invitationTtl       how long an invitation's emailed temporary password works,
 *                            checked at login and at first login
 */
@Validated
@ConfigurationProperties("app.auth")
public record AuthProperties(boolean refreshCookieSecure, @NotNull Duration invitationTtl) {
}
