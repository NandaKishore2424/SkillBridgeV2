package com.skillbridge.common.throttle;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;

import java.time.Duration;

/**
 * How hard each class of endpoint is limited.
 *
 * <p>One limit for everything is the thing this replaces. The old filter gave a
 * dashboard read and a login attempt the same 120 requests a minute, which is
 * far too loose for a brute-force target and tight enough to matter for normal
 * browsing. A tier cannot be both.
 *
 * <p><b>Two bandwidths each, and that pairing is the point.</b> The short one
 * absorbs a legitimate burst — somebody opening five tabs, a dashboard firing
 * four calls on load. The long one caps sustained abuse. A single bandwidth can
 * express one or the other and not both: set it low enough to stop an hour of
 * hammering and it rejects a normal page load.
 *
 * <p><b>Greedy versus intervally</b> is the other half. Greedy drips tokens back
 * continuously, so a second into a 60-per-minute limit you have one token again —
 * right for normal traffic, because it recovers smoothly. Intervally releases the
 * whole allowance when the interval elapses, so a failed attacker waits the full
 * minute instead of collecting a fresh attempt every twelve seconds. That is why
 * authentication is intervally and reads are greedy.
 */
public enum RateLimitTier {

    /**
     * Login and the password flows. Deliberately harsh, and intervally so a
     * rejected attacker gets nothing back until the window turns over.
     */
    AUTHENTICATION(
            Bandwidth.builder().capacity(5).refillIntervally(5, Duration.ofMinutes(1)).build(),
            Bandwidth.builder().capacity(20).refillIntervally(20, Duration.ofHours(1)).build()),

    /** Normal authenticated reads. */
    STANDARD(
            Bandwidth.builder().capacity(60).refillGreedy(60, Duration.ofMinutes(1)).build(),
            Bandwidth.builder().capacity(2_000).refillIntervally(2_000, Duration.ofHours(1)).build()),

    /** Writes. More expensive than reads and rarer, so tighter. */
    MUTATION(
            Bandwidth.builder().capacity(30).refillGreedy(30, Duration.ofMinutes(1)).build(),
            Bandwidth.builder().capacity(500).refillIntervally(500, Duration.ofHours(1)).build()),

    /**
     * Bulk upload and anything that fans out into the database or the AI
     * service. A handful an hour is generous for work a human initiates.
     */
    EXPENSIVE(
            Bandwidth.builder().capacity(5).refillIntervally(5, Duration.ofMinutes(5)).build(),
            Bandwidth.builder().capacity(50).refillIntervally(50, Duration.ofDays(1)).build()),

    /**
     * Unauthenticated endpoints, which can only be keyed by IP — and an IP is
     * shared by everyone behind one office NAT, so this stays modest rather than
     * harsh.
     */
    PUBLIC(
            Bandwidth.builder().capacity(20).refillGreedy(20, Duration.ofMinutes(1)).build(),
            Bandwidth.builder().capacity(300).refillIntervally(300, Duration.ofHours(1)).build());

    private final Bandwidth burst;
    private final Bandwidth sustained;

    RateLimitTier(Bandwidth burst, Bandwidth sustained) {
        this.burst = burst;
        this.sustained = sustained;
    }

    public BucketConfiguration configuration() {
        return BucketConfiguration.builder().addLimit(burst).addLimit(sustained).build();
    }

    /** The burst capacity, which is what {@code X-RateLimit-Limit} reports. */
    public long burstCapacity() {
        return burst.getCapacity();
    }
}
