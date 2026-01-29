package com.skillbridge.enrollment.repository;

import com.skillbridge.enrollment.entity.EnrollmentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for EnrollmentRequest entities
 */
@Repository
public interface EnrollmentRequestRepository extends JpaRepository<EnrollmentRequest, Long> {

    /**
     * Find all requests for a batch with a specific status
     */
    List<EnrollmentRequest> findByBatchIdAndStatus(Long batchId, EnrollmentRequest.RequestStatus status);

    /**
     * Find all requests by a trainer with a specific status
     */
    List<EnrollmentRequest> findByTrainerIdAndStatus(Long trainerId, EnrollmentRequest.RequestStatus status);

    /**
     * Find all requests with a specific status
     */
    List<EnrollmentRequest> findByStatus(EnrollmentRequest.RequestStatus status);

    /**
     * Find all pending requests
     */
    @Query("SELECT r FROM EnrollmentRequest r " +
            "WHERE r.status = 'PENDING' " +
            "ORDER BY r.createdAt DESC")
    List<EnrollmentRequest> findAllPending();

    /**
     * Count pending requests for a batch
     */
    long countByBatchIdAndStatus(Long batchId, EnrollmentRequest.RequestStatus status);

    /**
     * Count pending requests by a trainer
     */
    long countByTrainerIdAndStatus(Long trainerId, EnrollmentRequest.RequestStatus status);

    /**
     * Check if there's already a pending request for this combination
     */
    @Query("SELECT r FROM EnrollmentRequest r " +
            "WHERE r.batch.id = :batchId " +
            "AND r.student.id = :studentId " +
            "AND r.requestType = :requestType " +
            "AND r.status = 'PENDING'")
    Optional<EnrollmentRequest> findPendingRequest(
            @Param("batchId") Long batchId,
            @Param("studentId") Long studentId,
            @Param("requestType") EnrollmentRequest.RequestType requestType);

    /**
     * Find all requests for a specific student in a batch
     */
    List<EnrollmentRequest> findByBatchIdAndStudentId(Long batchId, Long studentId);

    /**
     * Find all requests by a trainer for a batch
     */
    List<EnrollmentRequest> findByBatchIdAndTrainerId(Long batchId, Long trainerId);
}
