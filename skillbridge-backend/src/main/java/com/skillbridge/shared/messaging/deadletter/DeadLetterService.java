package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.common.config.RabbitMQConfig;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.ConflictException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.shared.messaging.EventEnvelope;
import com.skillbridge.shared.messaging.outbox.OutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Dead letters: recording them, and the one decision each needs.
 *
 * <h2>A replay goes through the outbox</h2>
 *
 * <p>Not straight to the broker. The replay writes an outbox row and marks the dead
 * letter REPLAYED in one transaction, so the two cannot disagree: a replay that
 * rolls back sends nothing, and one that commits is delivered by the relay with its
 * retries and confirms. It also keeps {@code MessagingBoundaryRulesTest} true —
 * nothing but the relay publishes.
 *
 * <p>The replay is a <b>new event</b>, with a new id. The outbox's event id is
 * unique, and the consumer has already recorded the old id; a new one is what makes
 * it process the replay. The dead letter records which event its replay became.
 *
 * <h2>What can be replayed</h2>
 *
 * <p>A PENDING row whose body is a JSON object with an {@code eventType} this
 * system routes. The routing key comes from {@link RabbitMQConfig#ROUTING_KEYS},
 * not from the dead letter: a message that went through the retry tiers reached
 * the DLQ under a retry queue's name, and replaying it under that key would route
 * it nowhere. The checks are in {@link #replayBlocker}, which the listing reports
 * too, so the admin sees before clicking what the click would refuse.
 *
 * <h2>Why no {@code @Idempotent}</h2>
 *
 * <p>A replay is idempotent by construction. The row is locked and its status
 * checked, so a second replay of one row is a 409, and a repeated bulk replay finds
 * the rows it already resolved and skips them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterService {

    public static final int MAX_BATCH = 500;
    public static final int DEFAULT_BATCH = 100;

    static final String AGGREGATE_TYPE = "DeadLetterEvent";

    /** {@link #replayBlocker} reasons. Stable strings: a UI may switch on them. */
    public static final String NOT_JSON_OBJECT = "NOT_A_JSON_OBJECT";
    public static final String INVALID_ENVELOPE = "INVALID_ENVELOPE";
    public static final String UNKNOWN_EVENT_TYPE = "UNKNOWN_EVENT_TYPE";
    public static final String NOT_FOUND_OR_BUSY = "NOT_FOUND_OR_BEING_REPLAYED";

    private final DeadLetterEventRepository repository;
    private final OutboxWriter outbox;
    private final ObjectMapper objectMapper;

    /**
     * @return true if the message was new, false if it was already recorded
     */
    @Transactional
    public boolean record(DeadLetterMessage m) {
        int written = repository.insertIfAbsent(
                m.fingerprint(), m.eventId() != null ? m.eventId().toString() : null, m.eventType(),
                m.routingKey(), m.sourceQueue(), m.deathReason(), m.failureReason(), m.retryCount(),
                m.payload(), m.payloadEncoding(), m.payloadJson(), m.headersJson(), m.failedAt());
        return written == 1;
    }

    @Transactional(readOnly = true)
    public PagedResponse<DeadLetterDTO> list(DeadLetterStatus status, String eventType, int page, int size) {
        var pageable = Pagination.of(page, size);
        Page<DeadLetterEvent> rows = eventType == null || eventType.isBlank()
                ? repository.findByStatus(status, pageable)
                : repository.findByStatusAndEventType(status, eventType, pageable);
        return PagedResponse.from(rows, row -> DeadLetterDTO.summary(row, replayBlocker(row).orElse(null)));
    }

    @Transactional(readOnly = true)
    public DeadLetterDTO get(long id) {
        DeadLetterEvent row = repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Dead letter", id));
        return DeadLetterDTO.detail(row, replayBlocker(row).orElse(null), readTree(row.getHeaders()));
    }

    /**
     * Replays one dead letter as a new outbox event.
     *
     * @throws ConflictException     if it is no longer PENDING (409)
     * @throws BusinessRuleException if its body cannot be replayed (422)
     */
    @Transactional
    public UUID replay(long id, long adminId) {
        DeadLetterEvent row = repository.lockById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Dead letter", id));
        Optional<String> blocker = replayBlocker(row);
        if (blocker.isPresent()) {
            throw refusal(row, blocker.get());
        }
        return replayLocked(row, adminId);
    }

    @Transactional
    public DeadLetterDTO discard(long id, long adminId, String note) {
        if (note == null || note.isBlank()) {
            throw new BusinessRuleException("DISCARD_NOTE_REQUIRED", "Say why the dead letter is being discarded");
        }
        DeadLetterEvent row = repository.lockById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Dead letter", id));
        row.discarded(adminId, note.strip(), Instant.now());
        log.info("Dead letter {} (event {}) discarded by user {}: {}", id, row.getEventId(), adminId, note);
        return DeadLetterDTO.detail(row, replayBlocker(row).orElse(null), readTree(row.getHeaders()));
    }

    /**
     * Replays a selection of dead letters, or reports what it would replay.
     *
     * <p>One transaction: the rows are replayed together or not at all. What cannot
     * be replayed is skipped with its reason, not failed, so one malformed body does
     * not hold back the rest. The limit keeps that transaction short.
     */
    @Transactional
    public ReplayBatchResultDTO replayBatch(ReplayBatchRequest request, long adminId) {
        boolean dryRun = request.isDryRunRequested();
        int limit = Math.max(1, Math.min(MAX_BATCH, request.effectiveLimit()));

        List<DeadLetterEvent> rows;
        List<ReplayBatchResultDTO.Skipped> skipped = new ArrayList<>();
        if (request.hasIds()) {
            Set<Long> wanted = new LinkedHashSet<>(request.ids());
            rows = dryRun ? repository.findByIds(wanted) : repository.lockByIds(wanted);
            rows.forEach(r -> wanted.remove(r.getId()));
            // Absent, or locked by a replay running right now; either way, not ours to take.
            wanted.forEach(id -> skipped.add(new ReplayBatchResultDTO.Skipped(id, NOT_FOUND_OR_BUSY)));
        } else {
            String eventType = request.eventType() == null || request.eventType().isBlank()
                    ? null : request.eventType();
            rows = dryRun ? repository.findPending(eventType, limit) : repository.lockPending(eventType, limit);
        }

        List<ReplayBatchResultDTO.Replayed> replayed = new ArrayList<>();
        for (DeadLetterEvent row : rows) {
            Optional<String> blocker = replayBlocker(row);
            if (blocker.isPresent()) {
                skipped.add(new ReplayBatchResultDTO.Skipped(row.getId(), blocker.get()));
            } else if (dryRun) {
                replayed.add(new ReplayBatchResultDTO.Replayed(row.getId(), null));
            } else {
                replayed.add(new ReplayBatchResultDTO.Replayed(row.getId(), replayLocked(row, adminId)));
            }
        }

        boolean limitReached = !request.hasIds() && rows.size() == limit;
        log.info("Bulk dead-letter replay by user {} (dryRun={}): {} matched, {} {}, {} skipped",
                adminId, dryRun, rows.size(), replayed.size(), dryRun ? "replayable" : "replayed", skipped.size());
        return new ReplayBatchResultDTO(dryRun, rows.size(), replayed, skipped, limitReached);
    }

    @Transactional
    public int purgeResolvedBefore(Instant cutoff) {
        return repository.deleteResolvedBefore(cutoff);
    }

    /**
     * Why this row cannot be replayed, or empty if it can.
     *
     * <p>Reads {@code payload_json}, not {@code payload}: the recorder sets it only
     * for JSON that {@code jsonb} accepts, which is what the outbox will store.
     */
    Optional<String> replayBlocker(DeadLetterEvent row) {
        if (row.getStatus() != DeadLetterStatus.PENDING) {
            return Optional.of("ALREADY_" + row.getStatus());
        }
        JsonNode body = readTree(row.getPayloadJson());
        if (body == null || !body.isObject()) {
            return Optional.of(NOT_JSON_OBJECT);
        }
        // A body with an envelope's fields must be a whole envelope: the replay reads
        // its version from it, and half of one would go out under the wrong version.
        if (EventEnvelope.claimsToBeEnvelope(body) && !EventEnvelope.isEnvelope(body)) {
            return Optional.of(INVALID_ENVELOPE);
        }
        JsonNode type = body.get(EventEnvelope.EVENT_TYPE);
        if (type == null || !type.isTextual() || !RabbitMQConfig.ROUTING_KEYS.containsKey(type.asText())) {
            return Optional.of(UNKNOWN_EVENT_TYPE);
        }
        return Optional.empty();
    }

    /**
     * The caller holds the row lock and has checked {@link #replayBlocker}.
     *
     * <p>The body goes out in the schema version it failed in — an envelope with a new
     * event id, a version 1 body as it was. See {@link OutboxWriter#writeReplay}.
     */
    private UUID replayLocked(DeadLetterEvent row, long adminId) {
        JsonNode body = readTree(row.getPayloadJson());
        String eventType = body.get(EventEnvelope.EVENT_TYPE).asText();
        UUID eventId = outbox.writeReplay(eventType, RabbitMQConfig.ROUTING_KEYS.get(eventType),
                AGGREGATE_TYPE, row.getId(), body);
        row.replayed(adminId, eventId, Instant.now());
        log.info("Dead letter {} (event {}) replayed by user {} as event {}",
                row.getId(), row.getEventId(), adminId, eventId);
        return eventId;
    }

    private static RuntimeException refusal(DeadLetterEvent row, String blocker) {
        if (blocker.startsWith("ALREADY_")) {
            return new ConflictException("DEAD_LETTER_ALREADY_RESOLVED",
                    "Dead letter " + row.getId() + " is already " + row.getStatus());
        }
        return new BusinessRuleException("DEAD_LETTER_NOT_REPLAYABLE",
                "Dead letter " + row.getId() + " cannot be replayed: " + blocker);
    }

    private JsonNode readTree(String json) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }
}
