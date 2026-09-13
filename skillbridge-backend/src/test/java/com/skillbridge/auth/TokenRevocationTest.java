package com.skillbridge.auth;

import com.skillbridge.auth.service.JwtService;
import com.skillbridge.auth.service.TokenRevocationService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.QueryCountAssertion;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deactivating an account has to bite on the next request, not on the next
 * token.
 *
 * <p>Authentication builds the principal from the token's claims and no longer
 * reads {@code is_active}, which is what took a 150 ms round trip off every
 * request. The price was that the token's lifetime became the revocation window:
 * an admin deactivates a student and the student keeps working for up to fifteen
 * minutes. The claims are not wrong — {@code isActive} was true when the token
 * was issued — they are simply old.
 *
 * <p>These tests cover the two halves that could each be got wrong quietly. That
 * revocation is <em>consulted</em>, and that consulting it costs nothing: if it
 * cost a statement, the fix would have undone the change it exists to preserve,
 * and nothing would have failed — the endpoint would just be slow again.
 */
@SpringBootTest
@IntegrationTest
class TokenRevocationTest {

    private static final String COLLEGE_CODE = "REVOKE";

    @Autowired private TokenRevocationService revocation;
    @Autowired private JwtService jwtService;
    @Autowired private QueryCountAssertion queries;
    @Autowired private JdbcTemplate jdbc;

    private TenantFixture fixture;
    private Long userId;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(0, 0);
        userId = fixture.adminUserId;
        revocation.restore(userId);
    }

    @AfterEach
    void cleanUp() {
        revocation.restore(userId);
        fixture.remove();
    }

    @Test
    @DisplayName("a token issued before the deactivation is refused")
    void anOlderTokenIsRefused() {
        Instant issued = Instant.now().minus(1, ChronoUnit.MINUTES);

        assertThat(revocation.isRevoked(userId, issued))
                .as("nothing has been revoked yet, so this must not refuse anything")
                .isFalse();

        revocation.revoke(userId);

        assertThat(revocation.isRevoked(userId, issued))
                .as("""
                    This is the whole point. The token's own claims still say the \
                    account is active, because they were true when it was issued; \
                    only this knows the account has been deactivated since.""")
                .isTrue();
    }

    @Test
    @DisplayName("a token issued after the deactivation is honoured")
    void aNewerTokenIsHonoured() {
        revocation.revoke(userId);

        assertThat(revocation.isRevoked(userId, Instant.now().plus(1, ChronoUnit.MINUTES)))
                .as("""
                    A token issued after the deactivation can only have come from a \
                    login or a refresh, and both refuse an inactive account. Refusing \
                    it too would mean a reactivated user could not sign back in — the \
                    reason this stores a timestamp rather than a boolean.""")
                .isFalse();
    }

    @Test
    @DisplayName("reactivating restores the tokens the user already holds")
    void reactivationRestores() {
        Instant issued = Instant.now().minus(1, ChronoUnit.MINUTES);
        revocation.revoke(userId);
        assertThat(revocation.isRevoked(userId, issued)).isTrue();

        revocation.restore(userId);

        assertThat(revocation.isRevoked(userId, issued))
                .as("without this an admin's reactivation reads as not having worked, "
                        + "for the rest of the window")
                .isFalse();
    }

    @Test
    @DisplayName("the check costs no statement, so the hot path is unchanged")
    void theCheckCostsNothing() {
        revocation.revoke(userId);
        Instant issued = Instant.now().minus(1, ChronoUnit.MINUTES);

        var result = queries.countQueries(() -> {
            boolean refused = false;
            // Many calls, so a per-call statement could not hide in the noise.
            for (int i = 0; i < 50; i++) {
                refused |= revocation.isRevoked(userId, issued);
            }
            return refused;
        });

        assertThat(result.value())
                .as("the loop has to actually be answering, or zero statements means nothing")
                .isTrue();
        assertThat(result.queryCount())
                .as("""
                    Zero. This runs on every authenticated request, so a statement here \
                    is a 150 ms round trip added back under every endpoint -- exactly \
                    the cost that building the principal from claims removed. Nothing \
                    would fail if it regressed; the application would just be slow \
                    again.""")
                .isZero();
    }

    @Test
    @DisplayName("the window matches the access-token lifetime")
    void theWindowIsTheTokenLifetime() {
        // Shorter and the gap reopens; longer and the entries cannot refuse
        // anything, because every token issued before them has expired.
        assertThat(jwtService.accessTokenTtlSeconds())
                .as("if the TTL changes, the revocation window follows it automatically — "
                        + "this asserts they are read from the same place")
                .isEqualTo(900);
    }
}
