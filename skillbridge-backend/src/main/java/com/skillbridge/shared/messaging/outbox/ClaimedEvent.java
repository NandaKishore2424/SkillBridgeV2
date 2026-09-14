package com.skillbridge.shared.messaging.outbox;

import java.util.UUID;

/**
 * An outbox row a relay has claimed, as a plain value.
 *
 * <p>Not the entity. The relay holds this across a network call, outside any
 * transaction, and a managed entity carried across that boundary is how a stale
 * copy gets written back over a newer one. A record cannot be dirtied.
 *
 * @param attempts the attempt count INCLUDING this claim
 */
public record ClaimedEvent(long id, UUID eventId, String eventType, String routingKey,
                           String payload, String headers, int schemaVersion, int attempts) {

    static ClaimedEvent of(OutboxEvent row) {
        return new ClaimedEvent(row.getId(), row.getEventId(), row.getEventType(), row.getRoutingKey(),
                row.getPayload(), row.getHeaders(), row.getSchemaVersion(), row.getAttempts() + 1);
    }
}
