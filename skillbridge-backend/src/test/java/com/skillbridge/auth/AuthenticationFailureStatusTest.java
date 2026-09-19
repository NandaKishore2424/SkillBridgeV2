package com.skillbridge.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.JwtService;
import com.skillbridge.auth.service.TokenRevocationService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the API answers when it refuses a request.
 *
 * <p>The contract the SPA depends on: <b>401 means "refresh your token"</b>, 403
 * means "you may not". Before 2026-09-19 every authentication failure was an
 * empty 403 -- measured on 2026-09-17 for a missing, a garbage and an expired
 * token -- so the SPA never refreshed and sessions died after one access-token
 * lifetime.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@IntegrationTest
class AuthenticationFailureStatusTest {

    private static final String COLLEGE_CODE = "AUTHFAIL";
    private static final String PROTECTED = "/api/v1/admin/students?page=0&size=1";

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private TokenRevocationService revocation;
    @Autowired private ObjectMapper objectMapper;
    @Value("${jwt.secret}") private String secret;

    private TenantFixture fixture;
    private User admin;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(0, 0);
        admin = userRepository.findById(fixture.adminUserId).orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        revocation.restore(admin.getId());
        fixture.remove();
    }

    private String validToken() {
        return jwtService.generateAccessToken(admin, "COLLEGE_ADMIN", Set.of("COLLEGE_ADMIN"), false);
    }

    /** Correctly signed, with every claim a real token has, issued and expired at the given times. */
    private String signedToken(Instant issued, Instant expires) {
        Claims claims = jwtService.claims(validToken());
        return Jwts.builder()
                .claims(claims)
                .issuedAt(Date.from(issued))
                .expiration(Date.from(expires))
                .signWith(Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret)))
                .compact();
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        return rest.exchange("http://localhost:" + port + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        assertThat(response.getHeaders().getContentType())
                .as("an error body the client can parse, not an empty response")
                .isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        return objectMapper.readTree(response.getBody());
    }

    private void assertUnauthorized(ResponseEntity<String> response, String expectedChallenge) throws Exception {
        assertThat(response.getStatusCode().value())
                .as("an authentication failure must be 401 -- the SPA refreshes on 401 and only on 401")
                .isEqualTo(401);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo(expectedChallenge);
        JsonNode body = json(response);
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("error").asText()).isEqualTo("UNAUTHORIZED");
    }

    @Test
    @DisplayName("no token: 401, with a Bearer challenge")
    void noToken() throws Exception {
        assertUnauthorized(get(PROTECTED, null), "Bearer");
    }

    @Test
    @DisplayName("a garbage token: 401 invalid_token")
    void garbageToken() throws Exception {
        assertUnauthorized(get(PROTECTED, "not-a-jwt"), "Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("an expired, correctly signed token: 401 invalid_token -- the case that broke every session")
    void expiredToken() throws Exception {
        Instant now = Instant.now();
        String expired = signedToken(now.minusSeconds(7200), now.minusSeconds(3600));
        assertUnauthorized(get(PROTECTED, expired), "Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("a token issued before its user's sessions were revoked: 401 invalid_token")
    void revokedToken() throws Exception {
        Instant now = Instant.now();
        String olderToken = signedToken(now.minusSeconds(60), now.plusSeconds(600));
        revocation.revoke(admin.getId());
        assertUnauthorized(get(PROTECTED, olderToken), "Bearer error=\"invalid_token\"");
    }

    @Test
    @DisplayName("a valid token: 200 -- so the refusals above are about the token, not the endpoint")
    void validTokenIsAccepted() {
        assertThat(get(PROTECTED, validToken()).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("authenticated but refused by a URL rule: 403 JSON, no challenge")
    void refusedByUrlRule() throws Exception {
        ResponseEntity<String> response = get("/actuator/metrics", validToken());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(json(response).get("error").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("authenticated but refused by @PreAuthorize: 403 JSON in the same shape")
    void refusedByMethodSecurity() throws Exception {
        ResponseEntity<String> response = get("/api/v1/admin/dead-letters", validToken());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(json(response).get("error").asText()).isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("a stale token does not break a public endpoint")
    void staleTokenOnPublicEndpoint() {
        assertThat(get("/api/v1/colleges/active", "not-a-jwt").getStatusCode().value()).isEqualTo(200);
    }
}
