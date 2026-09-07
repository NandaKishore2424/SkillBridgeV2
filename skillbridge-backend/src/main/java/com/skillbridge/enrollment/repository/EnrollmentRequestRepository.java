package com.skillbridge.enrollment.repository;

import com.skillbridge.enrollment.domain.EnrollmentStatus;
import com.skillbridge.enrollment.entity.EnrollmentRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface EnrollmentRequestRepository extends JpaRepository<EnrollmentRequest, Long> {

    List<EnrollmentRequest> findByBatchIdAndStatus(Long batchId, EnrollmentStatus status);

    List<EnrollmentRequest> findByTrainerIdAndStatus(Long trainerId, EnrollmentStatus status);

    List<EnrollmentRequest> findByStatus(EnrollmentStatus status);

    /**
     * One student's applications, newest first.
     *
     * <p>Fetch-joins the batch because the DTO reads its name; the derived
     * finder this replaced took an extra select per row.
     */
    @Query(value = """
           SELECT r FROM EnrollmentRequest r
           JOIN FETCH r.batch
           WHERE r.student.id = :studentId
           ORDER BY r.createdAt DESC
           """,
           countQuery = """
           SELECT count(r) FROM EnrollmentRequest r
           WHERE r.student.id = :studentId
           """)
    Page<EnrollmentRequest> findByStudent(@Param("studentId") Long studentId, Pageable pageable);

    List<EnrollmentRequest> findByStudentIdAndStatus(Long studentId, EnrollmentStatus status);

    /**
     * Pending queue, with everything the DTO mapper reads already fetched.
     *
     * <p>Trainer is a LEFT join because a student application has none.
     */
    /**
     * Pending requests for one college.
     *
     * <p>This replaces {@code findAllPending()}, which had no college predicate
     * at all and returned every college's pending requests to whichever admin
     * asked. That was invisible because the Hibernate {@code collegeFilter} was
     * believed to scope it — it does not; see {@link TenantFilterAspect}. The
     * predicate is explicit here so the query is correct whether or not any
     * filter is enabled.
     */
    @Query(value = """
           SELECT r FROM EnrollmentRequest r
           JOIN FETCH r.batch
           JOIN FETCH r.student
           LEFT JOIN FETCH r.trainer
           WHERE r.status = com.skillbridge.enrollment.domain.EnrollmentStatus.PENDING
             AND r.collegeId = :collegeId
           ORDER BY r.createdAt DESC
           """,
           countQuery = """
           SELECT count(r) FROM EnrollmentRequest r
           WHERE r.status = com.skillbridge.enrollment.domain.EnrollmentStatus.PENDING
             AND r.collegeId = :collegeId
           """)
    Page<EnrollmentRequest> findPendingForCollege(@Param("collegeId") Long collegeId, Pageable pageable);

    /** Every pending request, across all colleges. SYSTEM_ADMIN only. */
    @Query(value = """
           SELECT r FROM EnrollmentRequest r
           JOIN FETCH r.batch
           JOIN FETCH r.student
           LEFT JOIN FETCH r.trainer
           WHERE r.status = com.skillbridge.enrollment.domain.EnrollmentStatus.PENDING
           ORDER BY r.createdAt DESC
           """,
           countQuery = """
           SELECT count(r) FROM EnrollmentRequest r
           WHERE r.status = com.skillbridge.enrollment.domain.EnrollmentStatus.PENDING
           """)
    Page<EnrollmentRequest> findAllPending(Pageable pageable);

    /**
     * One trainer's requests in a given state, newest first.
     *
     * <p>Fetch-joined for the same reason as the pending queues: the DTO mapper
     * reads the batch name, the student's name and roll number and the
     * trainer's name, which is four extra selects per row on the derived
     * finder this replaced.
     */
    @Query(value = """
           SELECT r FROM EnrollmentRequest r
           JOIN FETCH r.batch
           JOIN FETCH r.student
           LEFT JOIN FETCH r.trainer t
           WHERE t.id = :trainerId
             AND r.status = :status
           ORDER BY r.createdAt DESC
           """,
           countQuery = """
           SELECT count(r) FROM EnrollmentRequest r
           WHERE r.trainer.id = :trainerId
             AND r.status = :status
           """)
    Page<EnrollmentRequest> findByTrainerAndStatus(@Param("trainerId") Long trainerId,
                                                   @Param("status") EnrollmentStatus status,
                                                   Pageable pageable);

    long countByBatchIdAndStatus(Long batchId, EnrollmentStatus status);

    long countByTrainerIdAndStatus(Long trainerId, EnrollmentStatus status);

    long countByStudentIdAndStatus(Long studentId, EnrollmentStatus status);

    /**
     * An open request for this exact combination, if one exists.
     *
     * <p>Backed by the partial unique index {@code
     * uk_requests_one_pending_per_student_batch}, so this check and the database
     * constraint agree by construction rather than by convention.
     */
    @Query("""
           SELECT r FROM EnrollmentRequest r
           WHERE r.batch.id = :batchId
             AND r.student.id = :studentId
             AND r.requestType = :requestType
             AND r.status = com.skillbridge.enrollment.domain.EnrollmentStatus.PENDING
           """)
    Optional<EnrollmentRequest> findPendingRequest(@Param("batchId") Long batchId,
                                                   @Param("studentId") Long studentId,
                                                   @Param("requestType") EnrollmentRequest.RequestType requestType);

    List<EnrollmentRequest> findByBatchIdAndStudentId(Long batchId, Long studentId);

    List<EnrollmentRequest> findByBatchIdAndTrainerId(Long batchId, Long trainerId);

    /**
     * Expire applications to batches that have already started.
     *
     * <p>A bulk update rather than load-modify-save: this runs on a schedule over
     * rows nobody is editing, and pulling every stale request into the persistence
     * context to change one column would be pure waste.
     *
     * <p>The trade-off is that {@code @Version} is not incremented and no entity
     * callback fires, which is fine precisely because nothing else is touching
     * these rows — but it is the reason this pattern stays confined to the sweep.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
           UPDATE EnrollmentRequest r
           SET r.status = com.skillbridge.enrollment.domain.EnrollmentStatus.EXPIRED,
               r.reviewedAt = CURRENT_TIMESTAMP,
               r.decisionReason = 'Batch started before this application was reviewed',
               r.version = r.version + 1
           WHERE r.status = com.skillbridge.enrollment.domain.EnrollmentStatus.PENDING
             AND r.batch.startDate < :today
           """)
    int expireStaleRequests(@Param("today") LocalDate today);
}
