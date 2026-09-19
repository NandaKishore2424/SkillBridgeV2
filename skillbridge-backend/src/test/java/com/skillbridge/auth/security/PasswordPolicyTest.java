package com.skillbridge.auth.security;

import com.skillbridge.common.exception.WeakPasswordException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class PasswordPolicyTest {

    private static final String EMAIL = "priya.sharma@college.example";

    @Test
    @DisplayName("a long passphrase passes, with no digit or symbol required")
    void passphrasePasses() {
        assertThatNoException().isThrownBy(() -> PasswordPolicy.check("mango tree behind the library", EMAIL));
    }

    @Test
    @DisplayName("fewer than 15 characters fails, however complex")
    void shortFails() {
        assertThat(PasswordPolicy.violations("Xy7$kQ2!pL9#", EMAIL)).anyMatch(v -> v.contains("15"));
        assertThat(PasswordPolicy.violations("fourteen chars", EMAIL)).hasSize(1);
        assertThat(PasswordPolicy.violations("fifteen chars!!", EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("more than 128 characters fails")
    void tooLongFails() {
        assertThat(PasswordPolicy.violations("a".repeat(60) + "b".repeat(69), EMAIL)).anyMatch(v -> v.contains("128"));
    }

    @Test
    @DisplayName("built from the account's own email address fails, case-insensitively")
    void emailFails() {
        assertThat(PasswordPolicy.violations("Priya.Sharma-2026-secure", EMAIL)).anyMatch(v -> v.contains("email"));
    }

    @Test
    @DisplayName("built from the product's name fails")
    void productNameFails() {
        assertThat(PasswordPolicy.violations("my SkillBridge password", EMAIL)).anyMatch(v -> v.contains("site"));
    }

    @Test
    @DisplayName("one character or a short chunk repeated fails")
    void repetitionFails() {
        assertThat(PasswordPolicy.violations("aaaaaaaaaaaaaaaa", EMAIL)).anyMatch(v -> v.contains("repeat"));
        assertThat(PasswordPolicy.violations("abcabcabcabcabcabc", EMAIL)).anyMatch(v -> v.contains("repeat"));
        assertThat(PasswordPolicy.violations("abcdabcdabcdabcdX", EMAIL)).noneMatch(v -> v.contains("repeat"));
    }

    @Test
    @DisplayName("a long but common password fails, ignoring case and spaces")
    void commonFails() {
        assertThat(PasswordPolicy.violations("Correct Horse Battery Staple", EMAIL)).anyMatch(v -> v.contains("attackers"));
    }

    @Test
    @DisplayName("every broken rule is reported at once, in the exception's details")
    void allViolationsTogether() {
        assertThatThrownBy(() -> PasswordPolicy.check("aaaa", EMAIL))
                .isInstanceOf(WeakPasswordException.class)
                .satisfies(e -> assertThat(((WeakPasswordException) e).getDetails()).hasSize(2));
    }
}
