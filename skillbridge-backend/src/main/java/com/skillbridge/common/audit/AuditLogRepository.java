package com.skillbridge.common.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    Page<AuditLog> findByCollegeIdOrderByOccurredAtDesc(Long collegeId, Pageable pageable);

    Page<AuditLog> findByActorUserIdOrderByOccurredAtDesc(Long actorUserId, Pageable pageable);

    /** SYSTEM_ADMIN only — unscoped across every college. */
    Page<AuditLog> findByActionOrderByOccurredAtDesc(String action, Pageable pageable);

    /**
     * The tenant-scoped form of the above.
     *
     * <p>Both exist because filtering by action must not become a way around
     * the college scope: without this, a COLLEGE_ADMIN asking for
     * {@code ?action=LOGIN_FAILURE} would have been served every college's rows.
     */
    Page<AuditLog> findByCollegeIdAndActionOrderByOccurredAtDesc(Long collegeId, String action, Pageable pageable);

    Page<AuditLog> findAllByOrderByOccurredAtDesc(Pageable pageable);

    // ------------------------------------------------------------------
    // Keyset pagination
    // ------------------------------------------------------------------
    //
    // The offset finders above are kept for callers that want a numbered pager.
    // This table grows faster than any other in the system, and offset paging
    // degrades in a way that is invisible until it is not: LIMIT 20 OFFSET
    // 100000 makes Postgres read and discard a hundred thousand rows, so page
    // 5000 costs 250x page 1 for the same twenty rows. The seek below is
    // constant time at any depth because the WHERE clause jumps straight into
    // the index.
    //
    // The first page and the hundredth are the same query: Cursor.start()
    // supplies a bound strictly after every real row, so there is no "no
    // cursor yet" branch to get wrong. Binding a null instead would be a
    // runtime failure, not a style choice -- an untyped JDBC null makes
    // Postgres infer bytea and the row comparison stops type-checking.
    //
    // The comparison is a row constructor, `(a, b) < (c, d)`, which Postgres
    // evaluates lexicographically and can satisfy from a composite index. The
    // hand-expanded form -- `a < c OR (a = c AND b < d)` -- is equivalent and
    // the planner usually will not use the index for it.
    //
    // NOTE: the index these seeks want does not exist yet. Flyway was removed,
    // so it has to be applied to the database by hand:
    //
    //   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_log_college_seek
    //       ON audit_log (college_id, occurred_at DESC, id DESC);
    //   CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_log_seek
    //       ON audit_log (occurred_at DESC, id DESC);
    //
    // The column order matters: the equality predicate first, then the sort
    // key in the direction it is read. Until they exist these queries are
    // correct but scan, so the constant-time property keyset pagination is for
    // is not yet delivered -- only made reachable. Tracked in HANDOVER.md.
    //
    // Ordering by occurred_at alone would be non-deterministic across pages:
    // audit rows written by one request share a timestamp, so the tie-break on
    // id is what stops the seek skipping or repeating them.

    /** One college's trail, newest first. */
    @Query("""
           SELECT a FROM AuditLog a
           WHERE a.collegeId = :collegeId
             AND (a.occurredAt, a.id) < (:cursorTime, :cursorId)
           ORDER BY a.occurredAt DESC, a.id DESC
           """)
    List<AuditLog> seekByCollege(@Param("collegeId") Long collegeId,
                                 @Param("cursorTime") LocalDateTime cursorTime,
                                 @Param("cursorId") Long cursorId,
                                 Pageable limit);

    /** One college's trail, filtered by action. */
    @Query("""
           SELECT a FROM AuditLog a
           WHERE a.collegeId = :collegeId
             AND a.action = :action
             AND (a.occurredAt, a.id) < (:cursorTime, :cursorId)
           ORDER BY a.occurredAt DESC, a.id DESC
           """)
    List<AuditLog> seekByCollegeAndAction(@Param("collegeId") Long collegeId,
                                          @Param("action") String action,
                                          @Param("cursorTime") LocalDateTime cursorTime,
                                          @Param("cursorId") Long cursorId,
                                          Pageable limit);

    /** Every college. SYSTEM_ADMIN only. */
    @Query("""
           SELECT a FROM AuditLog a
           WHERE (a.occurredAt, a.id) < (:cursorTime, :cursorId)
           ORDER BY a.occurredAt DESC, a.id DESC
           """)
    List<AuditLog> seekAll(@Param("cursorTime") LocalDateTime cursorTime,
                           @Param("cursorId") Long cursorId,
                           Pageable limit);

    /** Every college, filtered by action. SYSTEM_ADMIN only. */
    @Query("""
           SELECT a FROM AuditLog a
           WHERE a.action = :action
             AND (a.occurredAt, a.id) < (:cursorTime, :cursorId)
           ORDER BY a.occurredAt DESC, a.id DESC
           """)
    List<AuditLog> seekByAction(@Param("action") String action,
                                @Param("cursorTime") LocalDateTime cursorTime,
                                @Param("cursorId") Long cursorId,
                                Pageable limit);
}
