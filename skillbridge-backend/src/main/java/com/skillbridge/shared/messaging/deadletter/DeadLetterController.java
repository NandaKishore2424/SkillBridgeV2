package com.skillbridge.shared.messaging.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.audit.AuditAction;
import com.skillbridge.common.audit.AuditLogService;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.exception.ApiException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.SystemAdminOnly;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The dead-letter queue, for the people who decide what happens to it.
 *
 * <p>SYSTEM_ADMIN only. Dead letters are not tenant-scoped — a failed event is an
 * operational fact about the platform — and a replay re-executes side effects, so
 * no college admin gets near it.
 *
 * <h2>Audit</h2>
 *
 * <p>Twice, deliberately. The dead letter itself records who resolved it and when,
 * in the same transaction as the replay: that record cannot disagree with what
 * happened. The audit log records the attempt, including refused ones, in its own
 * transaction, so a replay that failed is still on the trail.
 */
@RestController
@RequestMapping("/api/v1/admin/dead-letters")
@RequiredArgsConstructor
public class DeadLetterController {

    private static final String RESOURCE = "DeadLetterEvent";

    private final DeadLetterService deadLetters;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper;

    /** PENDING by default: what still needs a decision. Newest first. */
    @GetMapping
    @SystemAdminOnly
    public ResponseEntity<PagedResponse<DeadLetterDTO>> list(
            @RequestParam(defaultValue = "PENDING") DeadLetterStatus status,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(deadLetters.list(status, eventType, page, size));
    }

    /** One dead letter, with its body and headers. */
    @GetMapping("/{id}")
    @SystemAdminOnly
    public ResponseEntity<DeadLetterDTO> get(@PathVariable long id) {
        return ResponseEntity.ok(deadLetters.get(id));
    }

    /**
     * Republishes one dead letter as a new event.
     *
     * <p>202: the event is in the outbox, and the relay delivers it shortly. 409 if
     * it was already resolved, 422 if its body cannot be replayed.
     */
    @PostMapping("/{id}/replay")
    @SystemAdminOnly
    public ResponseEntity<ReplayBatchResultDTO.Replayed> replay(@PathVariable long id) {
        long adminId = SecurityUtils.currentUser().getId();
        UUID eventId = audited(AuditAction.DEAD_LETTER_REPLAYED, id, Map.of(),
                () -> deadLetters.replay(id, adminId));
        audit.record(AuditAction.DEAD_LETTER_REPLAYED, RESOURCE, id, AuditAction.OUTCOME_SUCCESS,
                json(Map.of("replayEventId", eventId.toString())));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ReplayBatchResultDTO.Replayed(id, eventId));
    }

    /** Marks a dead letter as not worth replaying. The note is required. */
    @PostMapping("/{id}/discard")
    @SystemAdminOnly
    public ResponseEntity<DeadLetterDTO> discard(@PathVariable long id, @Valid @RequestBody DiscardRequest request) {
        long adminId = SecurityUtils.currentUser().getId();
        DeadLetterDTO discarded = audited(AuditAction.DEAD_LETTER_DISCARDED, id, Map.of("note", request.note()),
                () -> deadLetters.discard(id, adminId, request.note()));
        audit.record(AuditAction.DEAD_LETTER_DISCARDED, RESOURCE, id, AuditAction.OUTCOME_SUCCESS,
                json(Map.of("note", request.note())));
        return ResponseEntity.ok(discarded);
    }

    /**
     * Replays a selection, after a systemic bug is fixed.
     *
     * <p>{@code dryRun} defaults to true: the response lists what would be replayed
     * and what would be skipped, and nothing changes. Send {@code "dryRun": false}
     * to replay. Only a real run is audited — a dry run changes nothing.
     */
    @PostMapping("/replay-batch")
    @SystemAdminOnly
    public ResponseEntity<ReplayBatchResultDTO> replayBatch(@Valid @RequestBody ReplayBatchRequest request) {
        long adminId = SecurityUtils.currentUser().getId();
        if (request.isDryRunRequested()) {
            return ResponseEntity.ok(deadLetters.replayBatch(request, adminId));
        }
        Map<String, Object> selection = new LinkedHashMap<>();
        selection.put("ids", request.ids());
        selection.put("eventType", request.eventType());
        selection.put("limit", request.effectiveLimit());
        ReplayBatchResultDTO result = audited(AuditAction.DEAD_LETTER_BATCH_REPLAYED, null, selection,
                () -> deadLetters.replayBatch(request, adminId));

        Map<String, Object> metadata = new LinkedHashMap<>(selection);
        metadata.put("replayed", result.replayed());
        metadata.put("skipped", result.skipped());
        audit.record(AuditAction.DEAD_LETTER_BATCH_REPLAYED, RESOURCE, null, AuditAction.OUTCOME_SUCCESS,
                json(metadata));
        return ResponseEntity.ok(result);
    }

    /** Runs {@code action}; if it is refused, the refusal is audited before it propagates. */
    private <T> T audited(String action, Long id, Map<String, ?> context, Supplier<T> call) {
        try {
            return call.get();
        } catch (ApiException e) {
            Map<String, Object> metadata = new LinkedHashMap<>(context);
            metadata.put("error", e.getErrorCode());
            metadata.put("message", e.getMessage());
            audit.record(action, RESOURCE, id, AuditAction.OUTCOME_FAILURE, json(metadata));
            throw e;
        }
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Audit metadata is context; losing it must not fail the replay that already happened.
            return null;
        }
    }
}
