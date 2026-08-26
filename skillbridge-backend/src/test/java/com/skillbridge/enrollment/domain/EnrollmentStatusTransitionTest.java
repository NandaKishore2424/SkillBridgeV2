package com.skillbridge.enrollment.domain;

import com.skillbridge.common.exception.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive coverage of the enrollment state machine.
 *
 * <p>Written as a full cross-product rather than a handful of examples: with six
 * states there are only 36 pairs, and enumerating all of them means a future
 * change to {@code allowedNext()} cannot quietly legalise a transition nobody
 * intended.
 */
class EnrollmentStatusTransitionTest {

    @Nested
    @DisplayName("legal transitions")
    class Legal {

        @Test
        @DisplayName("a pending request can be approved, rejected, withdrawn or expired")
        void pendingHasFourExits() {
            assertThat(EnrollmentStatus.PENDING.allowedNext())
                    .containsExactlyInAnyOrder(
                            EnrollmentStatus.APPROVED,
                            EnrollmentStatus.REJECTED,
                            EnrollmentStatus.WITHDRAWN,
                            EnrollmentStatus.EXPIRED);
        }

        @Test
        @DisplayName("an approved enrollment can still be cancelled")
        void approvedCanBeCancelled() {
            assertThatCode(() -> EnrollmentStatus.APPROVED
                    .assertCanTransitionTo(EnrollmentStatus.CANCELLED))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("illegal transitions")
    class Illegal {

        @Test
        @DisplayName("a rejected request cannot be approved afterwards")
        void rejectedCannotBeApproved() {
            assertThatThrownBy(() -> EnrollmentStatus.REJECTED
                    .assertCanTransitionTo(EnrollmentStatus.APPROVED))
                    .isInstanceOf(ConflictException.class)
                    // The message has to name both states — "invalid transition"
                    // in a bug report tells the reader nothing.
                    .hasMessageContaining("REJECTED")
                    .hasMessageContaining("APPROVED");
        }

        @Test
        @DisplayName("a withdrawn application cannot be revived")
        void withdrawnIsFinal() {
            assertThatThrownBy(() -> EnrollmentStatus.WITHDRAWN
                    .assertCanTransitionTo(EnrollmentStatus.PENDING))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("no state can transition to itself")
        void selfTransitionIsAlwaysIllegal() {
            for (EnrollmentStatus status : EnrollmentStatus.values()) {
                assertThat(status.canTransitionTo(status))
                        .as("%s -> %s should be illegal", status, status)
                        .isFalse();
            }
        }
    }

    @Nested
    @DisplayName("terminal states")
    class Terminal {

        @ParameterizedTest
        @EnumSource(value = EnrollmentStatus.class,
                names = { "REJECTED", "WITHDRAWN", "EXPIRED", "CANCELLED" })
        @DisplayName("have no exits")
        void areFinal(EnrollmentStatus status) {
            assertThat(status.isTerminal()).isTrue();
            assertThat(status.allowedNext()).isEmpty();
        }

        @ParameterizedTest
        @EnumSource(value = EnrollmentStatus.class, names = { "PENDING", "APPROVED" })
        @DisplayName("pending and approved are not terminal")
        void areNotFinal(EnrollmentStatus status) {
            assertThat(status.isTerminal()).isFalse();
        }
    }

    /**
     * Kept nested like the rest.
     *
     * <p>Surefire reports {@code @Nested} classes as separate test classes and,
     * in this setup, did not collect plain {@code @Test} methods sitting
     * alongside them on the outer class — they showed as {@code tests="0"} and
     * never ran. Uniform nesting keeps every assertion in this file visible in
     * the report.
     */
    @Nested
    @DisplayName("machine integrity")
    class Integrity {

        @Test
        @DisplayName("every one of the 36 state pairs behaves as the table declares")
        void fullCrossProduct() {
            for (EnrollmentStatus from : EnrollmentStatus.values()) {
                Set<EnrollmentStatus> legal = from.allowedNext();

                for (EnrollmentStatus to : EnrollmentStatus.values()) {
                    boolean expected = legal.contains(to);

                    assertThat(from.canTransitionTo(to))
                            .as("%s -> %s", from, to)
                            .isEqualTo(expected);

                    if (expected) {
                        assertThatCode(() -> from.assertCanTransitionTo(to))
                                .as("%s -> %s should be permitted", from, to)
                                .doesNotThrowAnyException();
                    } else {
                        assertThatThrownBy(() -> from.assertCanTransitionTo(to))
                                .as("%s -> %s should be refused", from, to)
                                .isInstanceOf(ConflictException.class);
                    }
                }
            }
        }

        @Test
        @DisplayName("every state is reachable from PENDING, so none is stranded")
        void everyStateIsReachable() {
            Set<EnrollmentStatus> reachable = EnumSet.of(EnrollmentStatus.PENDING);
            reachable.addAll(EnrollmentStatus.PENDING.allowedNext());
            EnrollmentStatus.PENDING.allowedNext()
                    .forEach(s -> reachable.addAll(s.allowedNext()));

            assertThat(reachable).containsExactlyInAnyOrder(EnrollmentStatus.values());
        }
    }
}
