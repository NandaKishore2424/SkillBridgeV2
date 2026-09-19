package com.skillbridge.auth.security;

import com.skillbridge.common.exception.ForbiddenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityUtilsTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(Long collegeId, String role) {
        AuthenticatedUser user = new AuthenticatedUser(1L, "u@example.invalid", collegeId, true, false, Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities()));
    }

    @Test
    @DisplayName("a caller with a college gets it back")
    void returnsCollege() {
        signIn(7L, "COLLEGE_ADMIN");
        assertThat(SecurityUtils.requireCollegeId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("a signed-in caller with no college gets 403, not a 401 that would send the SPA to refresh")
    void noCollegeIsForbidden() {
        signIn(null, "SYSTEM_ADMIN");
        assertThatThrownBy(SecurityUtils::requireCollegeId)
                .isInstanceOf(ForbiddenException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
    }
}
