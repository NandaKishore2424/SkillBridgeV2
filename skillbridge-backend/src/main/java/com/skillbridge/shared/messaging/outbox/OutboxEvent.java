package com.skillbridge.shared.messaging.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One event waiting to reach, or having reached, the broker.
 *
 * <p>Written by {@link OutboxWriter} inside the caller's transaction, so it
 * exists if and only if the business change it describes committed. Read and
 * updated only by {@link OutboxStore}, through targeted {@code UPDATE}s by id
 * rather than by dirtying this entity: the relay works with detached copies
 * across a network call, and a managed entity carried across that boundary is
 * how a stale write overwrites a newer one.
 *
 * <p>Deliberately has no setters. The lifecycle is the three repository updates
 * in {@link OutboxEventRepository}, each guarded by {@code status = 'PENDING'}.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** What a consumer deduplicates on. Delivery is at-least-once. */
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    /** Stored at write time rather than derived at relay time, so a routing change
     *  cannot re-route events already waiting. */
    @Column(name = "routing_key", nullable = false, length = 255)
    private String routingKey;

    /** The message body exactly as it will be published, already serialised. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", columnDefinition = "jsonb")
    private String headers;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    /** Incremented when a relay CLAIMS the row, not when it fails, so an event that
     *  crashes its relay every time still reaches DEAD instead of looping for ever. */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    /** A new, immediately due event. */
    public static OutboxEvent pending(String aggregateType, String aggregateId, String eventType,
                                      String routingKey, String payload, String headers,
                                      int schemaVersion, LocalDateTime now) {
        return OutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .schemaVersion(schemaVersion)
                .routingKey(routingKey)
                .payload(payload)
                .headers(headers)
                .status(OutboxStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build();
    }
}
