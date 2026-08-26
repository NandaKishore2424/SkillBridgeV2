package com.skillbridge.enrollment.repository;

import com.skillbridge.enrollment.domain.EnrollmentStatus;
import com.skillbridge.enrollment.entity.EnrollmentRequest;
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

    List<EnrollmentRequest> findByStudentIdOrderByCreatedAtDesc(Long studentId);

    List<EnrollmentRequest> findByStudentIdAndStatus(Long studentId, EnrollmentStatus status);

    /**
     * Pending queue, with everything the DTO mapper reads already fetched.
     *
     * <p>Trainer is a LEFT join because a student application has none.
     */
    @Query("""
           SELECT r FROM EnrollmentRequest r
           JOIN FETCH r.batch
           JOIN FETCH r.student
           LEFT JOIN FETCH r.trainer
           WHERE r.status = com.skillbridge.enrollment.domain.EnrollmentStatus.PENDING
           ORDER BY r.createdAt DESC
           """)
    List<EnrollmentRequest> findAllPending();

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
