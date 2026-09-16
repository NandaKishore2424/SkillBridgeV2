package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * One dead letter on the wire.
 *
 * <p>The list leaves out the body and headers ({@code payload} and {@code headers}
 * are null there, and omitted); {@code GET /{id}} includes them. A page of fifty
 * full bodies is a large response for a table whose point is to be skimmed.
 *
 * @param replayBlocker why this row cannot be replayed, or null if it can; the same
 *                      check the replay itself makes
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DeadLetterDTO(
        long id,
        UUID eventId,
        String eventType,
        String sourceQueue,
        String deathReason,
        String failureReason,
        int retryCount,
        Instant failedAt,
        Instant recordedAt,
        DeadLetterStatus status,
        Instant resolvedAt,
        Long resolvedBy,
        String resolutionNote,
        UUID replayEventId,
        String replayBlocker,
        String routingKey,
        String payloadEncoding,
        String payload,
        JsonNode headers) {

    static DeadLetterDTO summary(DeadLetterEvent e, String replayBlocker) {
        return new DeadLetterDTO(e.getId(), e.getEventId(), e.getEventType(), e.getSourceQueue(),
                e.getDeathReason(), e.getFailureReason(), e.getRetryCount(), e.getFailedAt(),
                e.getRecordedAt(), e.getStatus(), e.getResolvedAt(), e.getResolvedBy(),
                e.getResolutionNote(), e.getReplayEventId(), replayBlocker,
                null, null, null, null);
    }

    static DeadLetterDTO detail(DeadLetterEvent e, String replayBlocker, JsonNode headers) {
        return new DeadLetterDTO(e.getId(), e.getEventId(), e.getEventType(), e.getSourceQueue(),
                e.getDeathReason(), e.getFailureReason(), e.getRetryCount(), e.getFailedAt(),
                e.getRecordedAt(), e.getStatus(), e.getResolvedAt(), e.getResolvedBy(),
                e.getResolutionNote(), e.getReplayEventId(), replayBlocker,
                e.getRoutingKey(), e.getPayloadEncoding(), e.getPayload(), headers);
    }
}
