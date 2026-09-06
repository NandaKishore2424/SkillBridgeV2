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

    public String generateAccessToken(User user, String primaryRole) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTokenTtlSeconds);

        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim("email", user.getEmail())
                .claim("role", primaryRole)
                .claim("collegeId", user.getCollegeId())
                .claim("isActive", user.getIsActive())
                // No algorithm argument: 0.12 infers HS256 from the key length.
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
