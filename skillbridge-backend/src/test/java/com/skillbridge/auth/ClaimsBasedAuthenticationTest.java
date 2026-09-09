package com.skillbridge.auth;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.testsupport.TenantFixture;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Authentication now trusts the token instead of re-reading the user.
 *
 * <p>That removed a database round trip from every authenticated request, and
 * gave up immediate revocation to get it. These tests cover the parts of that
 * trade which could go wrong silently — every one of them is a property the
 * database read used to provide for free.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class ClaimsBasedAuthenticationTest {

    private static final String COLLEGE_CODE = "CLAIMSAUTH";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;

    private TenantFixture fixture;
    private User admin;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE).seed(0, 0);
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'COLLEGE_ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                fixture.adminUserId, roleId);
        admin = userRepository.findById(fixture.adminUserId).orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
    }

    private String tokenFor(User user, Set<String> roles, boolean mustChangePassword) {
        return jwtService.generateAccessToken(user, roles.iterator().next(), roles, mustChangePassword);
    }

    private int get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> response = rest.exchange("http://localhost:" + port + path,
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        return response.getStatusCode().value();
    }

    @Test
    @DisplayName("the token carries every role, not just the primary one")
    void tokenCarriesAllRoles() {
        String token = tokenFor(admin, Set.of("COLLEGE_ADMIN", "TRAINER"), false);
        Claims claims = jwtService.claims(token);

        List<String> roles = ((List<?>) claims.get("roles")).stream().map(String::valueOf).toList();
        assertThat(roles)
                .as("authorising on the `role` claim alone would silently drop the rest")
                .containsExactlyInAnyOrder("COLLEGE_ADMIN", "TRAINER");
        assertThat(claims.get("mustChangePassword", Boolean.class))
                .as("PasswordChangeRequiredFilter reads this; without it the flag defaults to false")
                .isFalse();
    }

    @Test
    @DisplayName("a role in the token is honoured without any database lookup")
    void claimsGrantAccess() {
        String token = tokenFor(admin, Set.of("COLLEGE_ADMIN"), false);

        assertThat(get("/api/v1/admin/students?page=0&size=1", token)).isEqualTo(200);
    }

    @Test
    @DisplayName("mustChangePassword in the token still confines the caller")
    void mustChangePasswordIsEnforcedFromTheToken() {
        // The flag lives only in the token now. If it were dropped, this request
        // would succeed -- which is the exact hole the flag was added to close.
        String token = tokenFor(admin, Set.of("COLLEGE_ADMIN"), true);

        assertThat(get("/api/v1/admin/students?page=0&size=1", token))
                .as("a user who must change their password may not reach the rest of the API")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a principal cannot be built with no roles")
    void rolelessPrincipalIsRefused() {
        // AuthService used to end primaryRole with .orElse("SYSTEM_ADMIN"), which
        // was inert while authorities came from the database and would have become
        // a privilege escalation the moment claims were trusted.
        assertThatThrownBy(() -> new AuthenticatedUser(1L, "x@y.z", 1L, true, false, Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no roles");
    }

    @Test
    @DisplayName("a token issued before the new claims falls back to the database")
    void oldTokensStillWork() {
        // Exactly the shape of a token minted before this change: no `roles`, no
        // `mustChangePassword`. Defaulting those would be wrong in the dangerous
        // direction, so the filter reloads the user for this request instead.
        String legacy = io.jsonwebtoken.Jwts.builder()
                .subject(String.valueOf(admin.getId()))
                .issuedAt(new java.util.Date())
                .expiration(new java.util.Date(System.currentTimeMillis() + 60_000))
                .claim("email", admin.getEmail())
                .claim("role", "COLLEGE_ADMIN")
                .claim("collegeId", admin.getCollegeId())
                .claim("isActive", true)
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        io.jsonwebtoken.io.Decoders.BASE64.decode(signingSecret())))
                .compact();

        assertThat(get("/api/v1/admin/students?page=0&size=1", legacy))
                .as("tokens in flight when this deployed must keep working until they expire")
                .isEqualTo(200);
    }

    @org.springframework.beans.factory.annotation.Value("${jwt.secret}")
    private String secret;

    /** Mirrors JwtService's own handling of a plain (non-Base64) secret. */
    private String signingSecret() {
        try {
            io.jsonwebtoken.io.Decoders.BASE64.decode(secret);
            return secret;
        } catch (Exception notBase64) {
            return java.util.Base64.getEncoder()
                    .encodeToString(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
