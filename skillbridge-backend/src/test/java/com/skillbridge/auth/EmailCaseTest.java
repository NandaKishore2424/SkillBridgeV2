package com.skillbridge.auth;

import com.skillbridge.auth.dto.LoginRequest;
import com.skillbridge.auth.entity.User;
import com.skillbridge.auth.repository.UserRepository;
import com.skillbridge.auth.service.AuthService;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One person, one account, whatever case their email is typed in.
 *
 * <p>{@code users.email} is unique but compared exactly. Before 2026-09-19 a
 * login typed as "Admin@..." failed for "admin@...", and an import of
 * "Asha@X.edu" beside "asha@x.edu" would have made a second account.
 */
@SpringBootTest
@IntegrationTest
class EmailCaseTest {

    private static final String PASSWORD = "correct horse battery staple 42";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthService authService;
    @Autowired private PasswordEncoder passwordEncoder;

    private TenantFixture fixture;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, "EMAILCASE");
        fixture.seed(0, 0);
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
    }

    @Test
    @DisplayName("login finds the account whatever case the email is typed in")
    void loginIgnoresCase() {
        jdbc.update("UPDATE users SET password_hash = ? WHERE id = ?", passwordEncoder.encode(PASSWORD),
                fixture.adminUserId);
        jdbc.update("INSERT INTO user_roles (user_id, role_id) SELECT ?, id FROM roles WHERE name = 'COLLEGE_ADMIN' "
                + "ON CONFLICT DO NOTHING", fixture.adminUserId);
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, fixture.adminUserId);

        assertThatNoException().isThrownBy(
                () -> authService.login(new LoginRequest("  " + email.toUpperCase() + " ", PASSWORD)));
    }

    @Test
    @DisplayName("an account saved with a mixed-case email is stored lower case")
    void entityNormalises() {
        User saved = userRepository.save(User.builder()
                .email("Mixed.Case@EmailCase.Example.Invalid")
                .passwordHash("x")
                .collegeId(fixture.collegeId)
                .isActive(true)
                .build());

        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("mixed.case@emailcase.example.invalid");
    }

    @Test
    @DisplayName("the database refuses a mixed-case email that bypassed the application")
    void databaseRefusesMixedCase() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO users (email, password_hash, college_id, is_active, created_at, updated_at)
                VALUES ('Bypass@EmailCase.Example.Invalid', 'x', ?, true, now(), now())
                """, fixture.collegeId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("users_email_lower_case");
    }
}
