package com.skillbridge.auth;

import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.testsupport.QueryCountAssertion;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authenticating a request must stay at one round trip.
 *
 * <p><b>Corrected 2026-09-10.</b> This used to say the filter loads the user on
 * every authenticated request. That was true when it was written and stopped
 * being true on 2026-09-09, when {@code TokenAuthenticationFilter} started
 * building the principal from the token's claims. The lookup this test measures
 * now runs on two paths rather than all of them: the filter's fallback for
 * tokens issued before that change, and {@code AuthService.describeCurrentUser}
 * behind {@code GET /auth/me}.
 *
 * <p>It is still worth an exact assertion. Against a database in another region a
 * statement is a round trip of roughly 150 ms — measured again on 2026-09-10 at
 * 142–268 ms — so a second statement here doubles the cost of every path that
 * still takes it. See docs/CONNECTION_POOL.md § 6 and docs/CACHING_STRATEGY.md
 * § 2, which is where the claim that this endpoint is hot was finally checked
 * against its callers.
 *
 * <p><b>That it is currently one statement is measured, not assumed.</b>
 * {@code roles} is an eager {@code @ManyToMany}, and eager does not by itself
 * promise a join — Hibernate is free to resolve a collection with a second
 * select, and does exactly that when loading a page of users, where
 * {@code @BatchSize} makes it one extra statement for all of them. For a single
 * entity it uses a join. Nothing in the mapping states that, which is why this
 * test exists rather than a comment.
 *
 * <p>This asserts an exact number, which {@link QueryCountAssertion} warns
 * against as a general rule — a count that may legitimately grow gets "fixed"
 * by raising the number, and then guards nothing. The exception is justified
 * here because this count must not grow: it is a per-request tax on every
 * endpoint, and the changes that would push it to two — making {@code roles}
 * lazy, or adding a second lookup to the filter — are precisely the ones worth
 * failing the build over.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DATABASE_URL", matches = ".+",
        disabledReason = "needs a PostgreSQL instance; set DATABASE_URL to run")
class AuthPathQueryCostTest {

    private static final String COLLEGE_CODE = "AUTHCOST";

    @Autowired private UserRepository userRepository;
    @Autowired private QueryCountAssertion queryCounter;
    @Autowired private JdbcTemplate jdbc;

    private TenantFixture fixture;
    private Long userId;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(0, 0);
        userId = fixture.adminUserId;
        Long roleId = jdbc.queryForObject("SELECT id FROM roles WHERE name = 'COLLEGE_ADMIN'", Long.class);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                userId, roleId);
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
    }

    @Test
    @DisplayName("the filter's user lookup costs one statement, roles included")
    void authLoadIsASingleStatement() {
        var result = queryCounter.countQueries(() -> {
            // Exactly what TokenAuthenticationFilter does.
            User user = userRepository.findById(userId).orElseThrow();
            // Touching the collection is what would trigger a second select if
            // the mapping ever stopped resolving it with the entity. Without
            // this line the test would pass while the filter still paid for two.
            user.getRoles().size();
            return user;
        });

        assertThat(result.value().getRoles())
                .as("roles must actually be loaded, or the low count means nothing was fetched")
                .isNotEmpty();
        assertThat(result.value().getIsActive())
                .as("isActive is what the filter reads; it has to be on the loaded entity")
                .isNotNull();

        assertThat(result.queryCount())
                .as("""
                    The auth path runs on every authenticated request, so a second \
                    statement here is ~150ms added to every endpoint at once. If this \
                    has gone to 2, check whether User.roles was made lazy or a second \
                    lookup was added to TokenAuthenticationFilter.""")
                .isEqualTo(1);
    }
}
