package com.skillbridge.enrollment.repository;

import com.skillbridge.enrollment.domain.EnrollmentState;
import com.skillbridge.enrollment.entity.Enrollment;
import com.skillbridge.enrollment.repository.projection.StudentStatsProjection;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

    List<Enrollment> findByBatchId(Long batchId);

    Optional<Enrollment> findByBatchIdAndStudentId(Long batchId, Long studentId);

    int countByBatchId(Long batchId);

    int countByBatchIdIn(List<Long> batchIds);

    int countByBatchIdAndStatus(Long batchId, EnrollmentState status);

    /**
     * Every dashboard counter in a single round trip.
     *
     * <p>The naive version is five separate {@code COUNT} queries, which is five
     * network round trips to a hosted database. At ~30ms each that is 150ms of
     * pure latency on the most-loaded page in the application before any work
     * happens.
     *
     * <p>{@code FILTER (WHERE ...)} is the SQL-standard conditional aggregate.
     * JPQL has no equivalent — you can fake it with
     * {@code SUM(CASE WHEN ... THEN 1 ELSE 0 END)}, but it reads worse and
     * Postgres evaluates the CASE for every row instead of skipping non-matches.
     * Native SQL is the right call here; this project is not changing database.
     */
    @Query(value = """
           SELECT
             COUNT(e.id)                                                  AS totalEnrolled,
             COUNT(e.id) FILTER (WHERE b.status = 'ACTIVE')               AS activeCount,
             COUNT(e.id) FILTER (WHERE b.status = 'COMPLETED')            AS completedCount,
             COUNT(e.id) FILTER (WHERE b.status IN ('UPCOMING','OPEN'))   AS upcomingCount,
             COALESCE((SELECT COUNT(*) FROM enrollment_requests r
                       WHERE r.student_id = :studentId AND r.status = 'PENDING'), 0)
                                                                          AS pendingRequests,
             COALESCE((SELECT COUNT(*) FROM topic_progress tp
                       WHERE tp.student_id = :studentId AND tp.status = 'COMPLETED'), 0)
                                                                          AS topicsCompleted,
             COALESCE((SELECT COUNT(*) FROM topic_progress tp
                       WHERE tp.student_id = :studentId), 0)              AS topicsAssigned
           FROM enrollments e
           JOIN batches b ON b.id = e.batch_id
           WHERE e.student_id = :studentId
             AND b.deleted_at IS NULL
           """, nativeQuery = true)
    StudentStatsProjection aggregateStatsForStudent(@Param("studentId") Long studentId);

    /**
     * A student's enrollments with the whole object graph the mapper touches.
     *
     * <p>{@code open-in-view} is off, so an unfetched association would throw
     * during serialisation rather than quietly issuing another query. The fetch
     * joins are not an optimisation here — they are what makes the endpoint work.
     */
    @Query("""
           SELECT DISTINCT e FROM Enrollment e
           JOIN FETCH e.batch b
           JOIN FETCH b.college
           WHERE e.student.id = :studentId
           ORDER BY b.startDate DESC
           """)
    List<Enrollment> findAllWithBatchDetailsByStudentId(@Param("studentId") Long studentId);

    @Query("SELECT e.batch.id FROM Enrollment e WHERE e.student.id = :studentId")
    List<Long> findBatchIdsByStudentId(@Param("studentId") Long studentId);

    @Query("""
           SELECT e.batch.id AS batchId, COUNT(e.id) AS enrolledCount
           FROM Enrollment e
           WHERE e.batch.id IN :batchIds
           GROUP BY e.batch.id
           """)
    List<Object[]> countGroupedByBatchIds(@Param("batchIds") List<Long> batchIds);

    /**
     * Count the live enrollments in a batch while holding a write lock on them.
     *
     * <p>This is the capacity check, and it is the one place a pessimistic lock is
     * warranted. Two students applying to the last seat at the same instant both
     * read "capacity - 1" under an ordinary read and both get in. Optimistic
     * locking cannot help: they are inserting different rows, so no version
     * collides. Serialising the count is what makes the limit a limit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT COUNT(e) FROM Enrollment e WHERE e.batch.id = :batchId AND e.status = 'ACTIVE'")
    long countActiveForUpdate(@Param("batchId") Long batchId);

    /** Active enrollments for one student. Guards student deletion. */
    int countByStudentIdAndStatus(Long studentId, EnrollmentState status);
}
