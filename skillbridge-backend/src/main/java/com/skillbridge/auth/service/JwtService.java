package com.skillbridge.auth.service;

import com.skillbridge.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.Base64;
import java.util.Date;

/**
 * Issues and verifies the application's access tokens.
 *
 * <p>Written against JJWT 0.12.x. The 0.11 API this replaced used JavaBean-style
 * setters on the builder ({@code setSubject}, {@code setExpiration}), a separate
 * {@code parserBuilder()} entry point, and an explicit
 * {@code SignatureAlgorithm} argument to {@code signWith}. All three are
 * deprecated or gone in 0.12: the builder reads as {@code subject(...)},
 * parsing starts at {@code Jwts.parser()}, and the algorithm is derived from the
 * key, which removes a whole class of mistake where a strong key is paired with
 * a weak or {@code none} algorithm.
 *
 * <p>Verification uses {@code verifyWith(SecretKey)} rather than the old
 * {@code setSigningKey(Object)}. The narrower type is the point: it will not
 * silently accept something that is not a MAC key.
 */
@Service
public class JwtService {

    private final SecretKey signingKey;
    private final long accessTokenTtlSeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.accessTokenTtlSeconds:3600}") long accessTokenTtlSeconds
    ) {
        // Throws WeakKeyException below 256 bits, which is the correct outcome:
        // an HS256 token signed with a short key is not worth issuing, and
        // failing at startup is better than failing per request.
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(encodeIfPlain(secret)));
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    /**
     * How long an access token this service issues stays valid.
     *
     * <p>Exists so {@code AuthResponse.expiresIn} can report the real number.
     * It carried a hard-coded {@code 3600L} at all three issuing sites, which
     * was right until the TTL was cut to 900 on 2026-09-09 and wrong by four
     * times afterwards -- and wrong in the direction that matters, since a
     * client scheduling a proactive refresh from it would wake up forty-five
     * minutes after its token had already expired.
     *
     * <p>Since that change this number is also the revocation window: the
     * filter trusts the token's claims rather than re-reading the user, so a
     * deactivated account keeps working until its access token runs out.
     */
    public long accessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    /**
     * Issues an access token carrying everything authorisation needs.
     *
     * <p><b>These claims are authoritative.</b> Since 2026-09-09
     * {@code TokenAuthenticationFilter} builds the principal from them instead
     * of re-reading the user on every request, so anything missing here is a
     * permission the request will not have, and anything stale here stays stale
     * until the token expires. Two claims exist only for that reason:
     *
     * <ul>
     *   <li>{@code roles} — every role, not just {@code role}. The single
     *       {@code role} claim is kept for the client, which displays it, but a
     *       user may hold more than one and authorising on the first alone would
     *       silently drop the rest.</li>
     *   <li>{@code mustChangePassword} — read by
     *       {@code PasswordChangeRequiredFilter}. It used to come from the
     *       entity; omitting it here would default it to false and reopen a hole
     *       this project has already closed once, where the flag was set on
     *       every bulk-provisioned account and enforced on none.</li>
     * </ul>
     *
     * <p>The token's lifetime is now the revocation window: a deactivated user
     * keeps access until it expires. See {@code jwt.accessTokenTtlSeconds}.
     */
    public String generateAccessToken(User user, String primaryRole, Set<String> roles,
                                      boolean mustChangePassword) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTokenTtlSeconds);

        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("email", user.getEmail())
                .claim("role", primaryRole)
                .claim("roles", List.copyOf(roles))
                .claim("collegeId", user.getCollegeId())
                .claim("isActive", user.getIsActive())
                .claim("mustChangePassword", mustChangePassword)
                // No algorithm argument: JJWT 0.12 picks the strongest HMAC the key
                // allows -- 32 bytes HS256, 48 HS384, 64 HS512. The documented
                // `openssl rand -base64 48` key, and the test key, give HS384.
                // JwtServiceAlgorithmTest pins it.
                .signWith(signingKey)
                .compact();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception ex) {
            // Expired, tampered, malformed, wrong algorithm, unsupported -- all
            // mean the same thing to a caller, and distinguishing them in a
            // response tells an attacker which part of their forgery to fix.
            return false;
        }
    }

    /**
     * The verified claims, for callers that need more than the subject.
     *
     * <p>Throws if the token is invalid or expired — parsing <em>is</em> the
     * verification, so there is no way to read a claim without having checked
     * the signature first.
     */
    public Claims claims(String token) {
        return parseClaims(token);
    }

    public Long getUserId(String token) {
        Claims claims = parseClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Accepts a Base64 secret, or Base64-encodes a plain one.
     *
     * <p>Kept from the previous implementation so an existing
     * {@code application-local.yaml} keeps working. Note the ambiguity it
     * carries: a plain secret made only of Base64 characters whose length is a
     * multiple of four is treated as already-encoded, so it decodes to
     * different bytes than it looks like. That does not weaken anything -- the
     * decoded value is still the shared secret both signing and verification
     * use -- but it means the effective key is not always the string you typed.
     * Generating the secret with {@code openssl rand -base64 48}, as the
     * templates instruct, avoids the question entirely.
     */
    private String encodeIfPlain(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret is required");
        }
        if (secret.matches("^[A-Za-z0-9+/=]+$") && secret.length() % 4 == 0) {
            return secret;
        }
        return Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8));
    }
}
