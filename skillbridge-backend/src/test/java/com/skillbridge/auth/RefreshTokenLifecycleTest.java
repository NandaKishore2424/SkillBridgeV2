package com.skillbridge.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.service.TokenRevocationService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole life of a session, over HTTP, as a browser drives it: login, the
 * refresh cookie, rotation, replay, theft, concurrent refreshes, logout, and a
 * password change. Every behaviour documented on AuthService is asserted here.
 */
// The Secure flag is pinned to its production default. Tests run with the
// `local` profile, so a developer's application-local.yaml (which turns it off for
// plain-http localhost) would otherwise decide what this test sees.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.auth.refresh-cookie-secure=true")
@IntegrationTest
class RefreshTokenLifecycleTest {

    private static final String COLLEGE_CODE = "REFRESHLC";
    private static final String PASSWORD = "a quiet river at dawn";
    private static final String NEW_PASSWORD = "seven ducks on a frozen pond";
    private static final Pattern COOKIE_VALUE = Pattern.compile("skillbridge_refresh_token=([^;]*)");

    @LocalServerPort private int port;
    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenRevocationService revocation;
    @Autowired private ObjectMapper objectMapper;

    private TenantFixture fixture;
    private String email;

    /** What a browser holds after an auth response: the body's access token and the Set-Cookie. */
    record Session(int status, String accessToken, String refreshCookie, String setCookie, JsonNode body) {
    }

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(0, 0);
        email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, fixture.adminUserId);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), fixture.adminUserId);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'COLLEGE_ADMIN'",
                fixture.adminUserId);
    }

    @AfterEach
    void cleanUp() {
        revocation.restore(fixture.adminUserId);
        jdbc.update("DELETE FROM refresh_tokens WHERE user_id = ?", fixture.adminUserId);
        fixture.remove();
    }

    // ------------------------------------------------------------------

    private Session post(String path, Object body, String cookie, String bearer) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            headers.add(HttpHeaders.COOKIE, "skillbridge_refresh_token=" + cookie);
        }
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        ResponseEntity<String> response = rest.exchange("http://localhost:" + port + path, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body == null ? Map.of() : body), headers), String.class);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        String cookieValue = null;
        if (setCookie != null) {
            Matcher m = COOKIE_VALUE.matcher(setCookie);
            cookieValue = m.find() ? m.group(1) : null;
        }
        JsonNode json = response.getBody() == null ? null : objectMapper.readTree(response.getBody());
        String access = json != null && json.hasNonNull("accessToken") ? json.get("accessToken").asText() : null;
        return new Session(response.getStatusCode().value(), access, cookieValue, setCookie, json);
    }

    private Session login(String password) throws Exception {
        return post("/api/v1/auth/login", Map.of("email", email, "password", password), null, null);
    }

    private Session refresh(String cookie) throws Exception {
        return post("/api/v1/auth/refresh", null, cookie, null);
    }

    private int getProtected(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        return rest.exchange("http://localhost:" + port + "/api/v1/admin/students?page=0&size=1",
                HttpMethod.GET, new HttpEntity<>(headers), String.class).getStatusCode().value();
    }

    private void ageRotation() {
        // Deterministic stand-in for waiting out the grace period: date the
        // rotation an hour back.
        jdbc.update("""
                UPDATE refresh_tokens SET revoked_at = now() - interval '1 hour'
                WHERE user_id = ? AND revoked = true
                """, fixture.adminUserId);
    }

    /**
     * Revocation is second-granular (a JWT's iat is whole seconds; see
     * TokenRevocationService.revoke), so a token issued in the same second as a
     * revocation legitimately survives it. Tests that assert an existing access
     * token dies wait for the next second first, or they would pass or fail on
     * timing.
     */
    private static void awaitNextSecond() throws InterruptedException {
        long now = System.currentTimeMillis();
        Thread.sleep(1000 - now % 1000 + 5);
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the refresh cookie")
    class Cookie {

        @Test
        @DisplayName("is HttpOnly, Secure, SameSite=Lax, scoped to /api/v1/auth, and lives as long as the token")
        void attributes() throws Exception {
            Session session = login(PASSWORD);
            assertThat(session.status()).isEqualTo(200);
            assertThat(session.setCookie())
                    .contains("HttpOnly")
                    .contains("Secure")
                    .contains("SameSite=Lax")
                    .contains("Path=/api/v1/auth")
                    .contains("Max-Age=1209600");
        }

        @Test
        @DisplayName("is the only place the refresh token travels: never in the response body")
        void notInTheBody() throws Exception {
            Session session = login(PASSWORD);
            assertThat(session.refreshCookie()).isNotBlank();
            assertThat(session.body().has("refreshToken"))
                    .as("a refresh token in a JSON body is readable by any script on the page")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("rotation and replay")
    class Rotation {

        @Test
        @DisplayName("each refresh issues a new token and retires the one presented")
        void rotates() throws Exception {
            Session first = login(PASSWORD);
            Session second = refresh(first.refreshCookie());
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.refreshCookie()).isNotEqualTo(first.refreshCookie());
            assertThat(getProtected(second.accessToken())).isEqualTo(200);
        }

        @Test
        @DisplayName("a replay within the grace period (another tab) is refused, but the session survives")
        void replayWithinGraceKeepsTheFamily() throws Exception {
            Session first = login(PASSWORD);
            Session second = refresh(first.refreshCookie());

            assertThat(refresh(first.refreshCookie()).status()).isEqualTo(401);

            assertThat(refresh(second.refreshCookie()).status())
                    .as("a tab losing a race must not log the user out everywhere")
                    .isEqualTo(200);
        }

        @Test
        @DisplayName("a replay after the grace period is theft: the whole family and every access token die")
        void replayAfterGraceRevokesEverything() throws Exception {
            Session first = login(PASSWORD);
            Session second = refresh(first.refreshCookie());
            ageRotation();
            awaitNextSecond();

            assertThat(refresh(first.refreshCookie()).status()).isEqualTo(401);

            assertThat(refresh(second.refreshCookie()).status())
                    .as("the successor was issued to whoever used the token first; it cannot be trusted either")
                    .isEqualTo(401);
            assertThat(getProtected(second.accessToken()))
                    .as("the access token that came with it is revoked too")
                    .isEqualTo(401);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM audit_log WHERE action = 'REFRESH_TOKEN_REUSE' AND actor_user_id = ?",
                    Long.class, fixture.adminUserId)).isEqualTo(1L);
        }

        @Test
        @DisplayName("of eight concurrent refreshes with one token, exactly one succeeds")
        void concurrentRefreshesRotateOnce() throws Exception {
            Session first = login(PASSWORD);
            int threads = 8;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Future<Session>> results = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    Callable<Session> call = () -> {
                        start.await();
                        return refresh(first.refreshCookie());
                    };
                    results.add(pool.submit(call));
                }
                start.countDown();
                List<Session> sessions = new ArrayList<>();
                for (Future<Session> f : results) {
                    sessions.add(f.get());
                }
                List<Session> winners = sessions.stream().filter(s -> s.status() == 200).toList();
                assertThat(winners)
                        .as("without the conditional UPDATE, every one of them rotated the same token")
                        .hasSize(1);
                assertThat(sessions).allSatisfy(s -> assertThat(s.status()).isIn(200, 401));
                assertThat(refresh(winners.get(0).refreshCookie()).status())
                        .as("the losers were within the grace period, so the winner's session lives on")
                        .isEqualTo(200);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("ending sessions")
    class Ending {

        @Test
        @DisplayName("logout retires the session's refresh token and clears the cookie")
        void logout() throws Exception {
            Session session = login(PASSWORD);
            Session loggedOut = post("/api/v1/auth/logout", null, session.refreshCookie(), null);
            assertThat(loggedOut.status()).isEqualTo(200);
            assertThat(loggedOut.setCookie()).contains("Max-Age=0");
            assertThat(refresh(session.refreshCookie()).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("a password change ends every other session and keeps the caller signed in")
        void passwordChangeEndsOtherSessions() throws Exception {
            Session laptop = login(PASSWORD);
            Session phone = login(PASSWORD);
            awaitNextSecond();

            Session changed = post("/api/v1/auth/change-password",
                    Map.of("oldPassword", PASSWORD, "newPassword", NEW_PASSWORD), null, laptop.accessToken());
            assertThat(changed.status()).isEqualTo(200);

            assertThat(refresh(phone.refreshCookie()).status()).as("the other device's refresh token").isEqualTo(401);
            assertThat(getProtected(phone.accessToken())).as("the other device's access token").isEqualTo(401);
            assertThat(getProtected(laptop.accessToken())).as("even the caller's old access token").isEqualTo(401);

            assertThat(getProtected(changed.accessToken())).as("the new session works").isEqualTo(200);
            assertThat(refresh(changed.refreshCookie()).status()).isEqualTo(200);
            assertThat(login(NEW_PASSWORD).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("a weak new password is refused with every broken rule listed, and nothing changes")
        void weakPasswordRefused() throws Exception {
            Session session = login(PASSWORD);
            Session refused = post("/api/v1/auth/change-password",
                    Map.of("oldPassword", PASSWORD, "newPassword", "short"), null, session.accessToken());

            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.body().get("error").asText()).isEqualTo("WEAK_PASSWORD");
            assertThat(refused.body().get("details").size()).isPositive();
            assertThat(getProtected(session.accessToken())).as("a refused change ends no session").isEqualTo(200);
            assertThat(login(PASSWORD).status()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("login failures look alike")
    class Failures {

        @Test
        @DisplayName("unknown email, wrong password, and wrong password on an inactive account: the same 401")
        void indistinguishable() throws Exception {
            Session unknown = post("/api/v1/auth/login", Map.of("email", "nobody@refreshlc.example.invalid",
                    "password", PASSWORD), null, null);
            Session wrong = login("not the password at all");
            jdbc.update("UPDATE users SET is_active = false WHERE id = ?", fixture.adminUserId);
            Session inactiveWrong = login("not the password at all");

            for (Session s : List.of(unknown, wrong, inactiveWrong)) {
                assertThat(s.status()).isEqualTo(401);
                assertThat(s.body().get("message").asText()).isEqualTo("Invalid email or password");
            }
        }

        @Test
        @DisplayName("only someone who knows the password learns the account is inactive")
        void inactiveRevealedOnlyWithThePassword() throws Exception {
            jdbc.update("UPDATE users SET is_active = false WHERE id = ?", fixture.adminUserId);
            Session s = login(PASSWORD);
            assertThat(s.status()).isEqualTo(401);
            assertThat(s.body().get("message").asText()).isEqualTo("Account is inactive");
        }

        @Test
        @DisplayName("first-login does not say whether an email exists")
        void firstLoginDoesNotEnumerate() throws Exception {
            Session unknown = post("/api/v1/auth/first-login", Map.of("email", "nobody@refreshlc.example.invalid",
                    "temporaryPassword", "whatever-temp", "newPassword", NEW_PASSWORD), null, null);
            Session wrongTemp = post("/api/v1/auth/first-login", Map.of("email", email,
                    "temporaryPassword", "whatever-temp", "newPassword", NEW_PASSWORD), null, null);

            assertThat(unknown.status()).isEqualTo(401);
            assertThat(wrongTemp.status()).isEqualTo(401);
            assertThat(unknown.body().get("message").asText()).isEqualTo(wrongTemp.body().get("message").asText());
        }
    }
}
