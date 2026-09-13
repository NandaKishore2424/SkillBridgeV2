package com.skillbridge.common.tenant;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The by-id tenancy check, and the trap it sits next to.
 *
 * <p>Before this class existed, a second college's administrator could read the
 * first college's students, trainers, batches, companies and enrollment lists by
 * id — names, email addresses and roll numbers — verified against the live
 * database. The Hibernate filter did not help and still would not: a filter
 * scopes <i>queries</i>, and {@code findById} resolves through
 * {@code Session.find()}, checking the persistence context and the second-level
 * cache before any filtered query is built.
 *
 * <p>No Spring context: this is a static method over a security principal, and
 * starting a container to exercise an if-statement would cost four seconds to
 * test four milliseconds of logic.
 *
 * <h2>The trap this file is also here to document</h2>
 *
 * <p>Gotcha 1 of this project, which bit twice in one day: <b>a tenant check is
 * not an authorization check.</b> {@code TenantGuard} answers "same college?" —
 * and classmates are. It says nothing about whether this <i>role</i> should see
 * this <i>resource</i>. Both have to be right, and passing one reads like
 * passing both. {@code passingTheTenantCheckIsNotPermission} below is a
 * regression test for the reasoning rather than the code: it shows a student
 * being waved through for a classmate's record, which is correct behaviour here
 * and a security bug at any call site that treats it as sufficient.
 */
class TenantGuardTest {

    private static final long COLLEGE_A = 1L;
    private static final long COLLEGE_B = 2L;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticateAs(Long collegeId, String... roles) {
        AuthenticatedUser principal = new AuthenticatedUser(
                99L, "guard-test@example.invalid", collegeId, true, false, Set.of(roles));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Nested
    @DisplayName("a college-scoped caller")
    class CollegeScoped {

        @Test
        @DisplayName("sees a resource in their own college")
        void ownCollegeIsVisible() {
            authenticateAs(COLLEGE_A, "COLLEGE_ADMIN");

            assertThat(TenantGuard.isVisible(COLLEGE_A)).isTrue();
            assertThatNoException()
                    .isThrownBy(() -> TenantGuard.requireVisible(COLLEGE_A, "Batch", 7L));
        }

        @Test
        @DisplayName("does not see another college's resource")
        void otherCollegeIsNotVisible() {
            authenticateAs(COLLEGE_A, "COLLEGE_ADMIN");

            assertThat(TenantGuard.isVisible(COLLEGE_B)).isFalse();
        }

        @Test
        @DisplayName("gets a 404, never a 403: a 403 would confirm the id exists")
        void refusalLooksLikeAMiss() {
            authenticateAs(COLLEGE_A, "COLLEGE_ADMIN");

            // The standing decision, and the reason it is not merely stylistic:
            // a 403 tells someone enumerating ids that this one is real and
            // belongs to somebody. An unauthorised read must be indistinguishable
            // from a miss.
            assertThatThrownBy(() -> TenantGuard.requireVisible(COLLEGE_B, "Student", 42L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Student")
                    .hasMessageContaining("42");
        }

        @Test
        @DisplayName("does not see an untenanted row")
        void nullCollegeIsNotVisible() {
            authenticateAs(COLLEGE_A, "COLLEGE_ADMIN");

            // A null owner is treated as invisible rather than as a wildcard.
            // The opposite default would make every row with a missing college_id
            // world-readable, and those exist: college_id is nullable on `users`.
            assertThat(TenantGuard.isVisible(null)).isFalse();
            assertThatThrownBy(() -> TenantGuard.requireVisible(null, "Company", 3L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("a SYSTEM_ADMIN")
    class SystemAdmin {

        @Test
        @DisplayName("is deliberately unscoped and sees every college")
        void seesEverything() {
            // SYSTEM_ADMIN has no college of their own -- collegeId is null,
            // which for any other role means "sees nothing". The role check has
            // to come first, and this test is what keeps it there.
            authenticateAs(null, "SYSTEM_ADMIN");

            assertThat(TenantGuard.isVisible(COLLEGE_A)).isTrue();
            assertThat(TenantGuard.isVisible(COLLEGE_B)).isTrue();
        }

        @Test
        @DisplayName("still does not see an untenanted row as a special case")
        void nullResourceCollegeIsStillVisibleToThem() {
            authenticateAs(null, "SYSTEM_ADMIN");

            // Documenting what the code does rather than asserting a preference:
            // the system-admin short-circuit returns true before the null check,
            // so an untenanted row IS visible to them. That is consistent with
            // "unscoped", and it is the branch a refactor is most likely to
            // reorder by accident.
            assertThat(TenantGuard.isVisible(null)).isTrue();
        }
    }

    @Nested
    @DisplayName("the trap")
    class NotAnAuthorizationCheck {

        @Test
        @DisplayName("passing the tenant check is not permission: classmates share a college")
        void passingTheTenantCheckIsNotPermission() {
            // A student, and a record belonging to a classmate. Same college, so
            // the guard passes -- correctly. It was never asked whether a student
            // may read another student.
            authenticateAs(COLLEGE_A, "STUDENT");

            assertThat(TenantGuard.isVisible(COLLEGE_A))
                    .as("same college, so this passes -- and says nothing about the role")
                    .isTrue();

            // This is gotcha 1 of the project. A call site that reads
            // `requireVisible(...)` and concludes "authorised" has checked half
            // the question. Any endpoint returning another student's record needs
            // a role check as well, and that check does not live here.
        }
    }
}
