package com.skillbridge.shared.messaging.deadletter;

import com.skillbridge.common.exception.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One message taken from {@code skillbridge.dlq}, and what a person decided about it.
 *
 * <p>Rows are written only by {@link DeadLetterEventRepository#insertIfAbsent}, a
 * native {@code INSERT .. ON CONFLICT DO NOTHING}, so everything describing the
 * message is {@code updatable = false}. The only thing that changes is the
 * decision, through {@link #replayed} and {@link #discarded}, on a row the caller
 * has locked.
 *
 * <p>{@code Instant} and {@code TIMESTAMPTZ}, unlike the rest of this schema: the
 * failure time arrives from the broker and the consumer as an absolute instant,
 * and there is no wall-clock reading of it that would be right in every zone.
 */
@Entity
@Table(name = "dead_letter_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterEvent {

    public static final String UTF8 = "utf8";
    public static final String BASE64 = "base64";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fingerprint", nullable = false, length = 64, updatable = false)
    private String fingerprint;

    /** The outbox event id the relay set as message_id; null if the message had none. */
    @Column(name = "event_id", updatable = false)
    private UUID eventId;

    @Column(name = "event_type", length = 100, updatable = false)
    private String eventType;

    /** As the message reached the DLQ. Often a retry queue's name, so a replay does not use it. */
    @Column(name = "routing_key", length = 255, updatable = false)
    private String routingKey;

    @Column(name = "source_queue", length = 255, updatable = false)
    private String sourceQueue;

    /** {@code consumer}, a broker x-death reason such as {@code delivery_limit}, or {@code unknown}. */
    @Column(name = "death_reason", nullable = false, length = 50, updatable = false)
    private String deathReason;

    @Column(name = "failure_reason", columnDefinition = "TEXT", updatable = false)
    private String failureReason;

    @Column(name = "retry_count", nullable = false, updatable = false)
    private int retryCount;

    /** The body verbatim, or base64 when it was not UTF-8 text; see {@link #payloadEncoding}. */
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    @Column(name = "payload_encoding", nullable = false, length = 10, updatable = false)
    private String payloadEncoding;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", columnDefinition = "jsonb", updatable = false)
    private String payloadJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", columnDefinition = "jsonb", updatable = false)
    private String headers;

    @Column(name = "failed_at", nullable = false, updatable = false)
    private Instant failedAt;

    @Column(name = "recorded_at", nullable = false, insertable = false, updatable = false)
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DeadLetterStatus status;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** A user id. No foreign key: the record of who decided outlives the account. */
    @Column(name = "resolved_by")
    private Long resolvedBy;

    @Column(name = "resolution_note", length = 500)
    private String resolutionNote;

    @Column(name = "replay_event_id")
    private UUID replayEventId;

    /**
     * Records a replay as the outbox event {@code replayEventId}.
     *
     * <p>The caller holds the row lock, so the status check cannot race another
     * replay; {@code chk_dead_letter_replayed} is the backstop if it ever does.
     */
    void replayed(long adminId, UUID replayEventId, Instant at) {
        requirePending();
        this.status = DeadLetterStatus.REPLAYED;
        this.resolvedBy = adminId;
        this.resolvedAt = at;
        this.replayEventId = replayEventId;
    }

    void discarded(long adminId, String note, Instant at) {
        requirePending();
        this.status = DeadLetterStatus.DISCARDED;
        this.resolvedBy = adminId;
        this.resolvedAt = at;
        this.resolutionNote = note;
    }

    public boolean isUtf8() {
        return UTF8.equals(payloadEncoding);
    }

    private void requirePending() {
        if (status != DeadLetterStatus.PENDING) {
            throw new ConflictException("DEAD_LETTER_ALREADY_RESOLVED",
                    "Dead letter " + id + " is already " + status);
        }
    }
}
