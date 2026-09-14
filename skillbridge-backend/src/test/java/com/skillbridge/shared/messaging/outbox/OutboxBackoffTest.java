package com.skillbridge.shared.messaging.outbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retry curve, exactly.
 *
 * <p>Pure arithmetic, so it gets exact assertions rather than "roughly grows".
 * The two properties that matter operationally are the cap — an event must not
 * wait hours between attempts — and that jitter only ever ADDS delay, so a burst
 * of events failing together is spread out after the base, never retried before
 * it.
 */
class OutboxBackoffTest {

    @ParameterizedTest(name = "attempt {0} waits {1}ms with no jitter")
    @CsvSource({
            "1, 500",
            "2, 1000",
            "3, 2000",
            "4, 4000",
            "8, 64000",
            "10, 256000"
    })
    @DisplayName("doubles from 500ms per attempt")
    void doublesPerAttempt(int attempts, long expectedMs) {
        assertThat(OutboxBackoff.delayAfter(attempts, 0.0)).isEqualTo(Duration.ofMillis(expectedMs));
    }

    @Test
    @DisplayName("is capped at five minutes, however many attempts")
    void isCapped() {
        assertThat(OutboxBackoff.delayAfter(11, 0.0)).isEqualTo(Duration.ofMinutes(5));
        assertThat(OutboxBackoff.delayAfter(40, 0.0)).isEqualTo(Duration.ofMinutes(5));
        // Shifting by a large raw attempt count would overflow; the exponent is
        // clamped, so an event somehow at attempt 1000 still waits the cap rather
        // than a negative number of milliseconds.
        assertThat(OutboxBackoff.delayAfter(1000, 0.0)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("jitter adds up to half again, and never subtracts")
    void jitterOnlyAdds() {
        assertThat(OutboxBackoff.delayAfter(3, 0.0)).isEqualTo(Duration.ofMillis(2000));
        assertThat(OutboxBackoff.delayAfter(3, 0.5)).isEqualTo(Duration.ofMillis(2500));
        assertThat(OutboxBackoff.delayAfter(3, 0.999)).isLessThan(Duration.ofMillis(3000));
    }

    @Test
    @DisplayName("out-of-range inputs are clamped rather than producing nonsense")
    void clampsInputs() {
        assertThat(OutboxBackoff.delayAfter(0, 0.0)).isEqualTo(Duration.ofMillis(500));
        assertThat(OutboxBackoff.delayAfter(-5, 0.0)).isEqualTo(Duration.ofMillis(500));
        assertThat(OutboxBackoff.delayAfter(1, -3.0)).isEqualTo(Duration.ofMillis(500));
        assertThat(OutboxBackoff.delayAfter(1, 7.0)).isEqualTo(Duration.ofMillis(750));
    }
}
