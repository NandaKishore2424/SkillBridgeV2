package com.skillbridge.shared.messaging.deadletter;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, Long> {

    /**
     * Records a dead letter unless its fingerprint is already here.
     *
     * <p>Native, and not {@code save}: {@code ON CONFLICT DO NOTHING} is what makes a
     * redelivered DLQ message a no-op instead of a unique-constraint failure that
     * would roll back and retry for ever. The UUID and JSON parameters arrive as text
     * and are cast here, so a null binds without the driver having to guess its type.
     *
     * @return 1 if a row was written, 0 if this message was already recorded
     */
    @Modifying
    @Query(value = """
            INSERT INTO dead_letter_events (
                fingerprint, event_id, event_type, routing_key, source_queue, death_reason,
                failure_reason, retry_count, payload, payload_encoding, payload_json, headers, failed_at)
            VALUES (
                :fingerprint, CAST(:eventId AS uuid), :eventType, :routingKey, :sourceQueue, :deathReason,
                :failureReason, :retryCount, :payload, :payloadEncoding,
                CAST(:payloadJson AS jsonb), CAST(:headers AS jsonb), :failedAt)
            ON CONFLICT (fingerprint) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("fingerprint") String fingerprint,
                       @Param("eventId") String eventId,
                       @Param("eventType") String eventType,
                       @Param("routingKey") String routingKey,
                       @Param("sourceQueue") String sourceQueue,
                       @Param("deathReason") String deathReason,
                       @Param("failureReason") String failureReason,
                       @Param("retryCount") int retryCount,
                       @Param("payload") String payload,
                       @Param("payloadEncoding") String payloadEncoding,
                       @Param("payloadJson") String payloadJson,
                       @Param("headers") String headers,
                       @Param("failedAt") Instant failedAt);

    /** Newest first. The sort is in the query so a caller's Pageable cannot reorder it. */
    @Query("SELECT d FROM DeadLetterEvent d WHERE d.status = :status ORDER BY d.id DESC")
    Page<DeadLetterEvent> findByStatus(@Param("status") DeadLetterStatus status, Pageable pageable);

    @Query("SELECT d FROM DeadLetterEvent d WHERE d.status = :status AND d.eventType = :eventType ORDER BY d.id DESC")
    Page<DeadLetterEvent> findByStatusAndEventType(@Param("status") DeadLetterStatus status,
                                                   @Param("eventType") String eventType,
                                                   Pageable pageable);

    /**
     * One row, locked until the transaction ends.
     *
     * <p>A second replay of the same row waits here, then reads it as already
     * REPLAYED and is refused. Without the lock both would see PENDING and both
     * would write an outbox event.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DeadLetterEvent d WHERE d.id = :id")
    Optional<DeadLetterEvent> lockById(@Param("id") Long id);

    /**
     * PENDING rows for a bulk replay, oldest first, skipping any another replay holds.
     *
     * <p>{@code SKIP LOCKED}, not a plain {@code FOR UPDATE}: two admins running the
     * same bulk replay take disjoint rows instead of one waiting to re-read rows the
     * other has just resolved. Every row is replayed exactly once either way.
     */
    @Query(value = """
            SELECT * FROM dead_letter_events
            WHERE status = 'PENDING'
              AND (CAST(:eventType AS varchar) IS NULL OR event_type = :eventType)
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DeadLetterEvent> lockPending(@Param("eventType") String eventType, @Param("limit") int limit);

    /** The same selection without locking, for a dry run, which must not hide rows from a real one. */
    @Query(value = """
            SELECT * FROM dead_letter_events
            WHERE status = 'PENDING'
              AND (CAST(:eventType AS varchar) IS NULL OR event_type = :eventType)
            ORDER BY id
            LIMIT :limit
            """, nativeQuery = true)
    List<DeadLetterEvent> findPending(@Param("eventType") String eventType, @Param("limit") int limit);

    /** Named rows in any status, so the result can say why a row was not replayed. */
    @Query(value = """
            SELECT * FROM dead_letter_events
            WHERE id IN (:ids)
            ORDER BY id
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<DeadLetterEvent> lockByIds(@Param("ids") Collection<Long> ids);

    @Query("SELECT d FROM DeadLetterEvent d WHERE d.id IN :ids ORDER BY d.id")
    List<DeadLetterEvent> findByIds(@Param("ids") Collection<Long> ids);

    /** Resolved rows past retention. PENDING rows are never purged: nobody has decided yet. */
    @Modifying
    @Query(value = """
            DELETE FROM dead_letter_events
            WHERE status <> 'PENDING' AND resolved_at < :cutoff
            """, nativeQuery = true)
    int deleteResolvedBefore(@Param("cutoff") Instant cutoff);
}
