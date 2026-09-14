package com.skillbridge.shared.messaging.outbox;

import java.time.Duration;

/**
 * How long to wait before retrying an event that failed to publish.
 *
 * <p>Exponential from 500ms, doubling per attempt, capped at five minutes, plus
 * up to half again as jitter. The jitter is not decoration: when a broker comes
 * back, every event that failed while it was down is due at once, and without
 * spreading them the relay hammers a broker that has just restarted.
 *
 * <p>Pure, so the curve is unit-tested exactly; the caller supplies the random
 * number.
 */
final class OutboxBackoff {

    static final Duration BASE = Duration.ofMillis(500);
    static final Duration CAP = Duration.ofMinutes(5);

    private OutboxBackoff() {
    }

    /**
     * @param attempts attempts made so far, at least 1
     * @param jitter   a number in [0, 1); values outside are clamped
     */
    static Duration delayAfter(int attempts, double jitter) {
        int exponent = Math.max(0, Math.min(attempts - 1, 20));   // 2^20 * 500ms already exceeds the cap
        long base = Math.min(BASE.toMillis() << exponent, CAP.toMillis());
        double fraction = Math.max(0.0, Math.min(jitter, 1.0));
        return Duration.ofMillis(base + (long) (base * 0.5 * fraction));
    }
}
