package com.skillbridge.shared.messaging.deadletter;

/**
 * Where a dead letter is in its one decision.
 *
 * <p>There is no way back to {@link #PENDING}. A replay that fails again arrives as
 * a new row with a new event id, so this row keeps recording what was decided
 * about it and when.
 */
public enum DeadLetterStatus {

    /** Waiting for a person. */
    PENDING,

    /** Republished through the outbox as {@code replay_event_id}. */
    REPLAYED,

    /** Decided against, with a note saying why. */
    DISCARDED
}
