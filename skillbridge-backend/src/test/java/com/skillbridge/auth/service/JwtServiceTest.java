package com.skillbridge.auth.service;

import com.skillbridge.auth.JwtProperties;
import com.skillbridge.auth.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the token service.
 *
 * <p>It had none, despite signing every credential the application issues. These
 * were written alongside the JJWT 0.11 to 0.12 upgrade: an API migration that
 * changes how tokens are signed and verified is exactly the change you want a
 * forgery test to be watching.
 */
class JwtServiceTest {

    /** 48 random bytes, Base64 — the shape `openssl rand -base64 48` produces. */
    private static final String SECRET =
            Base64.getEncoder().encodeToString(("skillbridge-test-secret-key-material-"
                    + "0123456789abcdef0123456789abcdef").getBytes());

    private static JwtService service(long ttlSeconds) {
        return new JwtService(new JwtProperties(SECRET, ttlSeconds, 1209600, 10));
    }

    private static User user(Long id, String email, Long collegeId) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setCollegeId(collegeId);
        u.setIsActive(true);
        return u;
    }

    @Nested
    @DisplayName("the advertised lifetime")
    class AdvertisedLifetime {

        /**
         * {@code AuthResponse.expiresIn} told every client 3600 while the token
         * lived 900.
         *
         * <p>The literal was correct when it was written and wrong from the
         * moment the TTL was cut on 2026-09-09 — and wrong in the direction that
         * matters, since a client scheduling a proactive refresh from it wakes up
         * forty-five minutes after its token has already expired. Nothing failed,
         * because this app refreshes reactively on a 401; the number was simply
         * published and untrue.
         *
         * <p>900 rather than the 3600 default deliberately: at the default this
         * assertion would have passed against the bug.
         */
        @Test
        void the_number_reported_to_clients_is_the_number_the_token_actually_gets() {
            JwtService jwt = service(900);

            io.jsonwebtoken.Claims claims = jwt.claims(jwt.generateAccessToken(
                    user(42L, "a@b.test", 7L), "TRAINER", java.util.Set.of("TRAINER"), false));

            long actualLifetime =
                    (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;

            assertEquals(900, jwt.accessTokenTtlSeconds());
            assertEquals(jwt.accessTokenTtlSeconds(), actualLifetime,
                    "expiresIn is built from this accessor, so a client is told exactly "
                            + "as long as the token really has");
        }

        /** No caller may reintroduce the literal the accessor exists to replace. */
        @Test
        void no_issuing_site_hard_codes_a_lifetime() throws Exception {
            String source = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/java/com/skillbridge/auth/service/AuthService.java"));

            assertFalse(source.matches("(?s).*\\.expiresIn\\(\\s*\\d.*"),
                    "AuthService hands expiresIn a numeric literal again — it duplicates "
                            + "jwt.accessTokenTtlSeconds and will drift from it silently");
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void issues_a_token_that_verifies_and_carries_the_subject() {
            JwtService jwt = service(3600);
            String token = jwt.generateAccessToken(user(42L, "a@b.test", 7L), "TRAINER", java.util.Set.of("TRAINER"), false);

            assertTrue(jwt.isTokenValid(token));
            assertEquals(42L, jwt.getUserId(token));
        }

        @Test
        void subject_survives_a_college_less_system_admin() {
            // collegeId is null for SYSTEM_ADMIN. A null claim must not break
            // serialisation or parsing.
            JwtService jwt = service(3600);
            String token = jwt.generateAccessToken(user(1L, "root@b.test", null), "SYSTEM_ADMIN", java.util.Set.of("SYSTEM_ADMIN"), false);

            assertTrue(jwt.isTokenValid(token));
            assertEquals(1L, jwt.getUserId(token));
        }
    }

    @Nested
    @DisplayName("rejection")
    class Rejection {

        @Test
        void rejects_a_token_signed_with_a_different_secret() {
            String foreign = Base64.getEncoder().encodeToString(
                    ("a-completely-different-key-of-sufficient-length-"
                            + "0123456789abcdef0123456789abcdef").getBytes());
            String token = new JwtService(new JwtProperties(foreign, 3600, 1209600, 10))
                    .generateAccessToken(user(1L, "a@b.test", 1L), "STUDENT", java.util.Set.of("STUDENT"), false);

            assertFalse(service(3600).isTokenValid(token),
                    "a token from another issuer must not verify");
        }

        @Test
        void rejects_a_tampered_payload() {
            JwtService jwt = service(3600);
            String token = jwt.generateAccessToken(user(1L, "a@b.test", 1L), "STUDENT", java.util.Set.of("STUDENT"), false);

            // Flip a character in the payload segment; the signature no longer matches.
            String[] parts = token.split("\\.");
            char[] payload = parts[1].toCharArray();
            payload[0] = payload[0] == 'e' ? 'f' : 'e';
            String tampered = parts[0] + "." + new String(payload) + "." + parts[2];

            assertFalse(jwt.isTokenValid(tampered));
        }

        @Test
        void rejects_an_expired_token() {
            // Negative TTL puts expiry in the past at the moment of issue.
            JwtService jwt = service(-60);
            String token = jwt.generateAccessToken(user(1L, "a@b.test", 1L), "STUDENT", java.util.Set.of("STUDENT"), false);

            assertFalse(jwt.isTokenValid(token));
        }

        @Test
        void rejects_an_unsigned_token() {
            // alg: none, the classic JWT forgery. parseSignedClaims must refuse it.
            String header = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"none\"}".getBytes());
            String payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"sub\":\"1\"}".getBytes());

            assertFalse(service(3600).isTokenValid(header + "." + payload + "."));
        }

        @Test
        void rejects_rubbish() {
            JwtService jwt = service(3600);
            assertFalse(jwt.isTokenValid("not-a-token"));
            assertFalse(jwt.isTokenValid(""));
        }
    }

    @Nested
    @DisplayName("secret handling")
    class SecretHandling {

        @Test
        void refuses_a_missing_secret() {
            assertThrows(IllegalArgumentException.class, () -> new JwtService(new JwtProperties(null, 3600, 1209600, 10)));
            assertThrows(IllegalArgumentException.class, () -> new JwtService(new JwtProperties("  ", 3600, 1209600, 10)));
        }

        @Test
        void refuses_a_secret_too_short_for_HS256() {
            // Under 256 bits JJWT raises WeakKeyException. Failing here, at
            // construction, is what stops a weak key reaching production.
            assertThrows(Exception.class, () -> new JwtService(new JwtProperties("c2hvcnQ=", 3600, 1209600, 10)));
        }

        @Test
        void accepts_a_plain_non_base64_secret_by_encoding_it() {
            String plain = "this is a plain text secret that is comfortably long enough for HS256!";
            assertDoesNotThrow(() -> {
                JwtService jwt = new JwtService(new JwtProperties(plain, 3600, 1209600, 10));
                String token = jwt.generateAccessToken(user(5L, "a@b.test", 1L), "STUDENT", java.util.Set.of("STUDENT"), false);
                assertTrue(jwt.isTokenValid(token));
            });
        }
    }
}
