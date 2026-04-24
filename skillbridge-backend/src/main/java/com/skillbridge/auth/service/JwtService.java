package com.skillbridge.auth.service;

import com.skillbridge.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.Key;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final Key signingKey;
    private final long accessTokenTtlSeconds;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.accessTokenTtlSeconds:3600}") long accessTokenTtlSeconds
    ) {
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(encodeIfPlain(secret)));
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public String generateAccessToken(User user, String primaryRole) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTokenTtlSeconds);

        return Jwts.builder()
                .setSubject(String.valueOf(user.getId()))
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(exp))
                .claim("email", user.getEmail())
                .claim("role", primaryRole)
                .claim("collegeId", user.getCollegeId())
                .claim("isActive", user.getIsActive())
                .signWith(signingKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public Long getUserId(String token) {
        Claims claims = parseClaims(token);
        return Long.parseLong(claims.getSubject());
    }

    private Claims parseClaims(String token) {
        return Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    private String encodeIfPlain(String secret) {
        // Allow plain secrets for dev; wrap into Base64 if needed.
        if (secret == null) {
            throw new IllegalArgumentException("JWT secret is required");
        }
        if (secret.matches("^[A-Za-z0-9+/=]+$") && secret.length() % 4 == 0) {
            return secret;
        }
        return java.util.Base64.getEncoder().encodeToString(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
