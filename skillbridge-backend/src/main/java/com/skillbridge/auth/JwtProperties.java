package com.skillbridge.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code jwt.*}.
 *
 * <p>One place, so the value cannot differ between the two classes that read
 * it. It did: {@code JwtService} defaulted the access TTL to 3600 while
 * {@code application.yaml} said 900, so a missing property quietly issued
 * hour-long tokens -- and that TTL is the revocation window for a change of
 * roles.
 *
 * @param secret                   base64, at least 32 bytes; the length picks the HMAC algorithm
 *                                 (48 bytes gives HS384, see JwtService)
 * @param accessTokenTtlSeconds    how long an access token lives
 * @param refreshTokenTtlSeconds   how long a refresh token, and its cookie, live
 * @param refreshReuseGraceSeconds how long after a rotation the old refresh token is still
 *                                 tolerated, for tabs that raced each other
 */
@Validated
@ConfigurationProperties("jwt")
public record JwtProperties(
        @NotBlank String secret,
        @Positive long accessTokenTtlSeconds,
        @Positive long refreshTokenTtlSeconds,
        @PositiveOrZero long refreshReuseGraceSeconds) {

    public Duration reuseGrace() {
        return Duration.ofSeconds(refreshReuseGraceSeconds);
    }
}
