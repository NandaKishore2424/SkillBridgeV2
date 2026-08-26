package com.skillbridge.auth.security;

import com.skillbridge.auth.entity.Role;
import com.skillbridge.auth.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the principal bug.
 *
 * <p>The raw {@link User} entity used to be the security principal. Because it
 * implements neither {@code UserDetails} nor {@code Principal},
 * {@code authentication.getName()} fell through to Lombok's generated
 * {@code toString()} and returned every field of the entity — password hash
 * included. Every feedback call passed that string to a service expecting an
 * email, so the feature was broken and leaking at the same time.
 */
class AuthenticatedUserTest {

    private User user(String... roleNames) {
        Set<Role> roles = java.util.Arrays.stream(roleNames)
                .map(name -> Role.builder().id(1L).name(name).build())
                .collect(java.util.stream.Collectors.toSet());

        return User.builder()
                .id(42L)
                .email("student@college.edu")
                .passwordHash("$2a$10$SOMETHINGTHATMUSTNEVERLEAK")
                .collegeId(9L)
                .isActive(true)
                .roles(roles)
                .build();
    }

    @Test
    @DisplayName("getUsername() returns the email, which is what getName() resolves to")
    void usernameIsTheEmail() {
        AuthenticatedUser principal = new AuthenticatedUser(user("STUDENT"));

        assertThat(principal.getUsername()).isEqualTo("student@college.edu");
        assertThat(principal.getEmail()).isEqualTo("student@college.edu");
    }

    @Test
    @DisplayName("the password hash is never carried on the principal")
    void passwordIsNeverExposed() {
        AuthenticatedUser principal = new AuthenticatedUser(user("STUDENT"));

        assertThat(principal.getPassword()).isNull();
    }

    @Test
    @DisplayName("toString() cannot leak the hash into a log line")
    void toStringIsSafe() {
        AuthenticatedUser principal = new AuthenticatedUser(user("STUDENT"));

        assertThat(principal.toString())
                .doesNotContain("$2a$10$")
                .contains("42")
                .contains("student@college.edu");
    }

    @Test
    @DisplayName("roles become ROLE_-prefixed authorities")
    void authoritiesArePrefixed() {
        AuthenticatedUser principal = new AuthenticatedUser(user("STUDENT", "TRAINER"));

        assertThat(principal.getAuthorities())
                .extracting(a -> a.getAuthority())
                .containsExactlyInAnyOrder("ROLE_STUDENT", "ROLE_TRAINER");
    }

    @Test
    @DisplayName("isSystemAdmin distinguishes the unscoped role")
    void systemAdminIsRecognised() {
        assertThat(new AuthenticatedUser(user("SYSTEM_ADMIN")).isSystemAdmin()).isTrue();
        assertThat(new AuthenticatedUser(user("COLLEGE_ADMIN")).isSystemAdmin()).isFalse();
    }

    @Test
    @DisplayName("an inactive account is disabled")
    void inactiveAccountIsDisabled() {
        User inactive = user("STUDENT");
        inactive.setIsActive(false);

        AuthenticatedUser principal = new AuthenticatedUser(inactive);

        assertThat(principal.isEnabled()).isFalse();
        assertThat(principal.isAccountNonLocked()).isFalse();
    }

    @Test
    @DisplayName("there is no ADMIN role — the guard that used to check for it matched nothing")
    void thereIsNoAdminRole() {
        assertThat(Role.RoleName.values())
                .extracting(Enum::name)
                .containsExactlyInAnyOrder("SYSTEM_ADMIN", "COLLEGE_ADMIN", "TRAINER", "STUDENT")
                .doesNotContain("ADMIN");
    }
}
