package com.skillbridge.common.idempotency;

/** Lifecycle of one idempotency record. Mirrored by {@code ck_idempotency_state}. */
public enum IdempotencyState {

    /** Claimed before the handler ran. A second request while this is set gets 409. */
    IN_PROGRESS,

    /** The handler finished and its response is stored for replay. */
    COMPLETED
}
