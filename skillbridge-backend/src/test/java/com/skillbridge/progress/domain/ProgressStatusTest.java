package com.skillbridge.progress.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class ProgressStatusTest {

    @ParameterizedTest
    @EnumSource(ProgressStatus.class)
    @DisplayName("every weight is a fraction between 0 and 1")
    void weightsAreFractions(ProgressStatus status) {
        assertThat(status.weight()).isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("weights are ordered PENDING < NEEDS_IMPROVEMENT < IN_PROGRESS < COMPLETED")
    void weightsAreOrdered() {
        assertThat(ProgressStatus.PENDING.weight())
                .isLessThan(ProgressStatus.NEEDS_IMPROVEMENT.weight());
        assertThat(ProgressStatus.NEEDS_IMPROVEMENT.weight())
                .isLessThan(ProgressStatus.IN_PROGRESS.weight());
        assertThat(ProgressStatus.IN_PROGRESS.weight())
                .isLessThan(ProgressStatus.COMPLETED.weight());
    }

    @Test
    @DisplayName("partially finished work is worth more than nothing")
    void partialWorkCounts() {
        // The reason weighting exists at all: a student with every topic started
        // and none signed off should not see 0%.
        assertThat(ProgressStatus.IN_PROGRESS.weight()).isGreaterThan(0.0);
        assertThat(ProgressStatus.NEEDS_IMPROVEMENT.weight()).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("only COMPLETED is terminal, and only NEEDS_IMPROVEMENT flags attention")
    void classification() {
        assertThat(ProgressStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(ProgressStatus.PENDING.isTerminal()).isFalse();
        assertThat(ProgressStatus.IN_PROGRESS.isTerminal()).isFalse();
        assertThat(ProgressStatus.NEEDS_IMPROVEMENT.isTerminal()).isFalse();

        assertThat(ProgressStatus.NEEDS_IMPROVEMENT.needsAttention()).isTrue();
        assertThat(ProgressStatus.COMPLETED.needsAttention()).isFalse();
    }

    @Test
    @DisplayName("a fully completed syllabus is exactly 100%")
    void fullCompletionIsExactlyOne() {
        // Guards against a weight of 0.99 quietly making 100% unreachable.
        assertThat(ProgressStatus.COMPLETED.weight()).isEqualTo(1.0);
    }
}
