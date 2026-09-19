package com.skillbridge.auth;

import com.skillbridge.auth.dto.LoginRequest;
import com.skillbridge.auth.service.AuthService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Twice as many simultaneous logins as the pool has connections.
 *
 * <p>Each login is a transaction, and until 2026-09-19 each wrote its audit row
 * in a {@code REQUIRES_NEW} transaction from inside it: a second connection,
 * requested while the first was held. Once every connection was held by a
 * login waiting for its audit connection, none could be had, and every one of
 * them waited out Hikari's connection-timeout and failed. That is pool
 * starvation by nested transactions, and it needs only as many concurrent
 * logins as the pool has connections.
 */
@SpringBootTest
@IntegrationTest
class ConcurrentLoginPoolTest {

    private static final String PASSWORD = "correct horse battery staple 42";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthService authService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private DataSource dataSource;

    private TenantFixture fixture;
    private String email;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, "POOLLOGIN");
        fixture.seed(0, 0);
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(PASSWORD), fixture.adminUserId);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'COLLEGE_ADMIN' "
                + "ON CONFLICT DO NOTHING", fixture.adminUserId);
        email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, fixture.adminUserId);
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
    }

    @Test
    @DisplayName("twice as many concurrent logins as connections all succeed")
    void loginsDoNotStarveThePool() throws Exception {
        int poolSize = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        int logins = poolSize * 2;
        CyclicBarrier start = new CyclicBarrier(logins);
        ExecutorService threads = Executors.newFixedThreadPool(logins);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < logins; i++) {
                results.add(threads.submit(() -> {
                    start.await();
                    return authService.login(new LoginRequest(email, PASSWORD));
                }));
            }
            List<String> failures = new ArrayList<>();
            for (Future<?> result : results) {
                try {
                    result.get(60, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failures.add(String.valueOf(e.getCause() == null ? e : e.getCause()));
                }
            }
            assertThat(failures).as("%d logins on a pool of %d", logins, poolSize).isEmpty();
        } finally {
            threads.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_user_id = ? AND action = 'LOGIN_SUCCESS'",
                Integer.class, fixture.adminUserId)).as("every login audited").isEqualTo(logins);
    }
}
