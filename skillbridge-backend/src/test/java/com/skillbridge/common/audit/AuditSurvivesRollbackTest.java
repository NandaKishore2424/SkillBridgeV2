package com.skillbridge.common.audit;

import com.skillbridge.auth.dto.LoginRequest;
import com.skillbridge.auth.service.AuthService;
import com.skillbridge.common.exception.UnauthorizedException;
import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The property the audit trail exists for: the record of a failed or denied
 * action outlives the transaction that failed. Before 2026-09-19 that came
 * from REQUIRES_NEW; now from {@link AuditLogWriter}'s own connection. Nothing
 * tested it under either.
 */
@SpringBootTest
@IntegrationTest
class AuditSurvivesRollbackTest {

    @Autowired private AuditLogService audit;
    @Autowired private AuthService authService;
    @Autowired private TransactionTemplate transactions;
    @Autowired private JdbcTemplate jdbc;

    private final String marker = "rollback-" + UUID.randomUUID() + "@example.invalid";

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log WHERE actor_email = ?", marker);
    }

    @Test
    @DisplayName("an entry written inside a transaction that rolls back is still there")
    void survivesTheCallersRollback() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            audit.recordAnonymous(AuditAction.LOGIN_FAILURE, marker, AuditAction.OUTCOME_FAILURE, null);
            throw new IllegalStateException("the business change fails");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rows()).isOne();
    }

    @Test
    @DisplayName("a failed login is recorded although the login transaction rolls back")
    void failedLoginIsAudited() {
        assertThatThrownBy(() -> authService.login(new LoginRequest(marker, "not the password at all")))
                .isInstanceOf(UnauthorizedException.class);

        assertThat(rows()).isOne();
    }

    private Integer rows() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_email = ?", Integer.class, marker);
    }
}
