package com.skillbridge.shared.messaging.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.observability.CorrelationIdFilter;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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

    /** Bumped only for a breaking payload change; see contracts/ai-events. */
    static final int CURRENT_SCHEMA_VERSION = 1;

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;

    /**
     * @return the event id, which is also what consumers deduplicate on
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(String aggregateType, Object aggregateId, String eventType,
                      String routingKey, Object payload) {
        OutboxEvent event = OutboxEvent.pending(
                aggregateType, String.valueOf(aggregateId), eventType, routingKey,
                toJson(payload), toJson(currentHeaders()), CURRENT_SCHEMA_VERSION,
                LocalDateTime.now());
        repository.save(event);
        return event.getEventId();
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
