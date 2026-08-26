package com.skillbridge.progress.entity;

import com.skillbridge.progress.domain.ProgressStatus;
import com.skillbridge.trainer.entity.Trainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lifecycle-timestamp contract of {@code TopicProgress.record}.
 *
 * <p>These invariants are cheap to state and expensive to discover the absence
 * of: a COMPLETED row with no {@code completedAt}, or a re-opened row still
 * carrying a stale one, only shows up months later in a report.
 */
class TopicProgressRecordTest {

    private TopicProgress progress;
    private Trainer trainer;

    @BeforeEach
    void setUp() {
        progress = TopicProgress.builder()
                .status(ProgressStatus.PENDING)
                .build();

        trainer = new Trainer();
        trainer.setId(7L);
        trainer.setFullName("A Trainer");
    }

    @Test
    @DisplayName("leaving PENDING stamps startedAt")
    void firstGradeStampsStart() {
        assertThat(progress.getStartedAt()).isNull();

        progress.record(ProgressStatus.IN_PROGRESS, trainer, "Making progress", null);

        assertThat(progress.getStartedAt()).isNotNull();
        assertThat(progress.getStatus()).isEqualTo(ProgressStatus.IN_PROGRESS);
        assertThat(progress.getUpdatedBy()).isSameAs(trainer);
    }

    @Test
    @DisplayName("startedAt is not overwritten by later gradings")
    void startIsStampedOnlyOnce() {
        progress.record(ProgressStatus.IN_PROGRESS, trainer, null, null);
        var firstStart = progress.getStartedAt();

        progress.record(ProgressStatus.COMPLETED, trainer, null, 90);

        assertThat(progress.getStartedAt()).isEqualTo(firstStart);
    }

    @Test
    @DisplayName("COMPLETED stamps completedAt")
    void completionIsStamped() {
        progress.record(ProgressStatus.COMPLETED, trainer, "Signed off", 88);

        assertThat(progress.getCompletedAt()).isNotNull();
        assertThat(progress.getScore()).isEqualTo(88);
    }

    @Test
    @DisplayName("moving away from COMPLETED clears completedAt")
    void reopeningClearsCompletion() {
        progress.record(ProgressStatus.COMPLETED, trainer, "Signed off", 88);
        assertThat(progress.getCompletedAt()).isNotNull();

        // A trainer taking a second look and sending it back is a normal event,
        // not an error — but the row must stop claiming it was completed.
        progress.record(ProgressStatus.NEEDS_IMPROVEMENT, trainer, "Reworked on review", 40);

        assertThat(progress.getCompletedAt()).isNull();
        assertThat(progress.getStatus()).isEqualTo(ProgressStatus.NEEDS_IMPROVEMENT);
    }

    @Test
    @DisplayName("a score outside 0-100 is refused")
    void scoreIsBounded() {
        assertThatThrownBy(() -> progress.record(ProgressStatus.COMPLETED, trainer, null, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 100");

        assertThatThrownBy(() -> progress.record(ProgressStatus.COMPLETED, trainer, null, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null status is refused")
    void statusIsRequired() {
        assertThatThrownBy(() -> progress.record(null, trainer, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a null score is allowed — pass/fail grading carries no number")
    void scoreIsOptional() {
        progress.record(ProgressStatus.COMPLETED, trainer, "Fine", null);

        assertThat(progress.getScore()).isNull();
        assertThat(progress.getStatus()).isEqualTo(ProgressStatus.COMPLETED);
    }

    @Test
    @DisplayName("weight() tracks the current status")
    void weightFollowsStatus() {
        assertThat(progress.weight()).isEqualTo(ProgressStatus.PENDING.weight());

        progress.record(ProgressStatus.COMPLETED, trainer, null, null);

        assertThat(progress.weight()).isEqualTo(1.0);
    }
}
