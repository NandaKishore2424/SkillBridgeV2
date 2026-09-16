package com.skillbridge.shared.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.observability.CorrelationIdFilter;
import com.skillbridge.shared.messaging.EventEnvelope;
import com.skillbridge.shared.messaging.EventType;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Records an event in the caller's transaction.
 *
 * <p>This is the whole of "publishing" now. The event and the business change it
 * describes commit together or roll back together, because they are two rows in
 * one transaction. {@link OutboxRelay} delivers it to the broker afterwards.
 *
 * <p>The stored payload is the whole {@link EventEnvelope}, not just the event's
 * fields, and the relay publishes it byte for byte. The envelope's {@code eventId}
 * is the row's {@code event_id} and the AMQP {@code message_id}: one id, everywhere.
 *
 * <h2>Why MANDATORY</h2>
 *
 * <p>A call from outside a transaction fails loudly rather than opening one of its
 * own. A standalone insert would commit whether or not the business change did,
 * which is precisely the dual-write problem this class exists to remove — so
 * silently "working" there would be the worst outcome available.
 *
 * <h2>Why nothing is swallowed</h2>
 *
 * <p>The old publisher caught every broker exception so a student's save would
 * not fail because RabbitMQ was down, and the cost was that the event vanished.
 * That trade is gone. The broker is no longer on this path at all; what can fail
 * here is an INSERT, and an insert that fails means the database is in trouble —
 * in which case the business transaction should fail too.
 */
@Service
@RequiredArgsConstructor
public class OutboxWriter {

    /**
     * The largest payload the outbox takes: 64 KiB.
     *
     * <p>Every event here is a handful of ids. The bound exists for {@link OutboxRelay},
     * which treats a broker's refusal as the broker's problem, not the event's — true
     * only if no event is large enough for a broker to refuse on its own merits. A
     * larger payload is a producer bug, and it fails the business transaction here,
     * rather than stalling the relay later.
     */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * Records a new event, in the envelope, at its type's current schema version.
     *
     * @param collegeId the tenant the event belongs to; null only for an event that belongs to none
     * @param payload   the event's own fields, in the shape {@code type}'s schema version defines
     * @return the event id, which is also what consumers deduplicate on
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(EventType type, String aggregateType, Object aggregateId, Long collegeId, Object payload) {
        UUID eventId = UUID.randomUUID();
        Map<String, String> headers = currentHeaders();
        EventEnvelope<Object> envelope = new EventEnvelope<>(
                eventId, type.name(), type.schemaVersion(),
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                aggregateType, String.valueOf(aggregateId), collegeId,
                headers.get(CorrelationIdFilter.MDC_TRACE), null, payload);
        return save(eventId, aggregateType, aggregateId, type.name(), type.routingKey(),
                type.schemaVersion(), toJson(envelope), headers);
    }

    /**
     * Records a dead letter's body to be sent again, in the version it failed in.
     *
     * <p>Not re-enveloped and not upgraded: what failed is what is replayed, and a
     * consumer that has been fixed to handle it will. An envelope gets a new event id
     * (see {@link EventEnvelope#forReplay}); a version 1 body, which has nowhere to
     * carry one, goes as it was, under a new AMQP message id.
     *
     * @return the new event id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID writeReplay(String eventType, String routingKey, String aggregateType, Object aggregateId,
                            JsonNode body) {
        UUID eventId = UUID.randomUUID();
        return save(eventId, aggregateType, aggregateId, eventType, routingKey,
                EventEnvelope.schemaVersionOf(body), toJson(EventEnvelope.forReplay(body, eventId)),
                currentHeaders());
    }

    private UUID save(UUID eventId, String aggregateType, Object aggregateId, String eventType, String routingKey,
                      int schemaVersion, String payload, Map<String, String> headers) {
        int size = payload.getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_PAYLOAD_BYTES) {
            throw new IllegalStateException("outbox payload for " + eventType + " is " + size
                    + " bytes; the limit is " + MAX_PAYLOAD_BYTES);
        }
        repository.save(OutboxEvent.pending(
                eventId, aggregateType, String.valueOf(aggregateId), eventType, routingKey,
                payload, toJson(headers), schemaVersion, LocalDateTime.now()));
        return eventId;
    }

    /**
     * The request's correlation context, carried to the consumer.
     *
     * <p>Without it an asynchronous chain is several unrelated log trails, and
     * "why was this student never analysed" becomes a search by timestamp.
     */
    private static Map<String, String> currentHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String key : new String[]{CorrelationIdFilter.MDC_TRACE,
                CorrelationIdFilter.MDC_USER, CorrelationIdFilter.MDC_TENANT}) {
            String value = MDC.get(key);
            if (value != null) {
                headers.put(key, value);
            }
        }
        return headers;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // A payload that cannot be serialised is a programming error, and it
            // must fail the business transaction rather than commit a change whose
            // event can never be sent.
            throw new IllegalStateException("outbox payload is not serialisable: " + value, e);
        }
    }
}
