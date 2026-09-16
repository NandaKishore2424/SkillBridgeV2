package com.skillbridge.shared.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * The outbox's persistence. Every state change is a targeted {@code UPDATE} by id,
 * guarded by {@code status = 'PENDING'}, so a late or duplicate write cannot move
 * a row out of PUBLISHED or DEAD.
 */
@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Due rows, locked for this transaction and skipped by everyone else's.
     *
     * <p>{@code FOR UPDATE} alone would make a second relay BLOCK behind the
     * first. {@code SKIP LOCKED} makes it take the next rows instead, so any
     * number of relays process disjoint batches with no coordination. That is
     * why the relay needs no advisory lock of its own.
     *
     * <p>Must run inside the same transaction as {@link #lease}: the locks are
     * what keep two relays from leasing the same row.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY id
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> lockDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * Claims locked rows by moving them out of the due window and counting the attempt.
     *
     * <p>This is the whole crash story. If the relay dies after this commits, the
     * rows are still PENDING and come due again when {@code leaseUntil} passes.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET next_attempt_at = :leaseUntil, attempts = attempts + 1
            WHERE id IN (:ids) AND status = 'PENDING'
            """, nativeQuery = true)
    int lease(@Param("ids") Collection<Long> ids, @Param("leaseUntil") LocalDateTime leaseUntil);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET status = 'PUBLISHED', published_at = :at, last_error = NULL
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markPublished(@Param("id") Long id, @Param("at") LocalDateTime at);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET next_attempt_at = :nextAttemptAt, last_error = :error
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int scheduleRetry(@Param("id") Long id, @Param("error") String error,
                      @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    /**
     * Gives claimed rows back without charging them the attempt the claim counted.
     *
     * <p>For a broker that was unavailable: the attempt said nothing about these
     * events, so it must not bring them closer to DEAD. {@code GREATEST} because a
     * row can only be here after a claim incremented it, but a floor costs nothing.
     */
    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET attempts = GREATEST(attempts - 1, 0), next_attempt_at = :nextAttemptAt, last_error = :error
            WHERE id IN (:ids) AND status = 'PENDING'
            """, nativeQuery = true)
    int releaseUncharged(@Param("ids") Collection<Long> ids, @Param("error") String error,
                         @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET status = 'DEAD', last_error = :error
            WHERE id = :id AND status = 'PENDING'
            """, nativeQuery = true)
    int markDead(@Param("id") Long id, @Param("error") String error);

    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE status = 'PUBLISHED' AND published_at < :cutoff
            """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);

    long countByStatus(OutboxStatus status);
}
