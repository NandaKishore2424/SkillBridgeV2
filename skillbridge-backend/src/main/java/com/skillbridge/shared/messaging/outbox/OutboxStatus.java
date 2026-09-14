package com.skillbridge.shared.messaging.outbox;

/**
 * Where an outbox event is in its life.
 *
 * <p>Three states, and the absence of a fourth is deliberate. There is no
 * {@code IN_FLIGHT}: a relay claims a batch by pushing {@code next_attempt_at}
 * forward as a lease, so a relay that dies mid-batch leaves its rows
 * {@code PENDING} and they simply become due again. An in-flight status would
 * need a sweeper to rescue rows stuck in it after a crash, and would be one more
 * place an event could be lost.
 */
public enum OutboxStatus {

    /** Not yet confirmed by the broker. Includes rows whose last attempt failed. */
    PENDING,

    /** The broker confirmed it. Kept for a retention window as an audit. */
    PUBLISHED,

    /** Gave up after the maximum number of attempts. Needs a human. */
    DEAD
}
