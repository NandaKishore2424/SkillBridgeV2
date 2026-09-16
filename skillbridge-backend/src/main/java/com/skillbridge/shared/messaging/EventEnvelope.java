package com.skillbridge.shared.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * What every event looks like on the wire since schema version 2.
 *
 * <p>A consumer can route, deduplicate, trace and version-check an event from these
 * fields alone, without understanding its payload — and so can the dead-letter
 * recorder, and a replay, and a person reading a stored message a month later. That
 * is why the version is in the body, not only in an AMQP header: a message copied to
 * the DLQ, stored, and sent again keeps its body, and the body alone has to say what
 * it is. {@code contracts/ai-events/v2/ai-event.schema.json} is the definition.
 *
 * <p>{@code occurredAt} is text, not an {@code Instant}, so the wire format does not
 * depend on how whichever {@code ObjectMapper} serialises it was configured.
 *
 * <h2>Version 1 had no envelope</h2>
 *
 * <p>A version 1 event is a bare object, {@code {"eventType", "studentId",
 * "collegeId", "metadata"}}. Nothing produces those any more, but a dead letter or a
 * message already in a queue can still be one, so {@link #schemaVersionOf} treats a
 * body without an envelope as version 1 rather than as an error.
 *
 * @param replayOf only on a replay: the event id this event re-sends
 */
@JsonPropertyOrder({"eventId", "eventType", "schemaVersion", "occurredAt", "aggregateType",
        "aggregateId", "collegeId", "traceId", "replayOf", "payload"})
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String occurredAt,
        String aggregateType,
        String aggregateId,
        Long collegeId,
        String traceId,
        @JsonInclude(JsonInclude.Include.NON_NULL) UUID replayOf,
        T payload) {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String SCHEMA_VERSION = "schemaVersion";
    public static final String REPLAY_OF = "replayOf";
    public static final String PAYLOAD = "payload";

    /** The version of every event published before the envelope existed. */
    public static final int UNENVELOPED_VERSION = 1;

    /** Whether a body claims to be an envelope: it has either field only an envelope has. */
    public static boolean claimsToBeEnvelope(JsonNode body) {
        return body != null && body.isObject() && (body.has(SCHEMA_VERSION) || body.has(PAYLOAD));
    }

    /** Whether a body is a well-formed envelope, as far as routing and replay need to know. */
    public static boolean isEnvelope(JsonNode body) {
        return claimsToBeEnvelope(body)
                && body.path(SCHEMA_VERSION).canConvertToInt()
                && body.path(SCHEMA_VERSION).isIntegralNumber()
                && body.path(SCHEMA_VERSION).asInt() >= 1
                && body.path(PAYLOAD).isObject()
                && body.path(EVENT_TYPE).isTextual();
    }

    /** The body's schema version: its own if it is an envelope, 1 if it predates them. */
    public static int schemaVersionOf(JsonNode body) {
        return isEnvelope(body) ? body.get(SCHEMA_VERSION).asInt() : UNENVELOPED_VERSION;
    }

    /**
     * A copy of an envelope, re-addressed for a replay.
     *
     * <p>A new {@code eventId}, because the consumer has already recorded the old one
     * and would acknowledge the replay as a duplicate without processing it. The old
     * id goes in {@code replayOf}, so the two can be joined. Nothing else changes:
     * {@code occurredAt} is still when the business change happened, and the payload
     * and its version are exactly what failed. A body that is not an envelope is
     * returned unchanged — version 1 had nowhere to put an id.
     */
    public static JsonNode forReplay(JsonNode body, UUID newEventId) {
        if (!isEnvelope(body)) {
            return body;
        }
        ObjectNode copy = body.deepCopy();
        JsonNode previous = copy.get(EVENT_ID);
        copy.put(EVENT_ID, newEventId.toString());
        if (previous != null && previous.isTextual()) {
            copy.put(REPLAY_OF, previous.asText());
        }
        return copy;
    }
}
