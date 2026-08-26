package com.skillbridge.enrollment.controller;

import com.skillbridge.enrollment.dto.BatchEnrollmentDTO;
import com.skillbridge.enrollment.dto.EnrolledStudentDTO;
import com.skillbridge.enrollment.dto.EnrollmentRequestDTO;
import com.skillbridge.enrollment.service.EnrollmentManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin enrollment management: direct add/remove of students, and approval or
 * rejection of trainer requests.
 *
 * <p>Every endpoint here was guarded by {@code hasRole('ADMIN')} until this
 * change. No such role exists — {@link com.skillbridge.auth.entity.Role.RoleName}
 * defines SYSTEM_ADMIN, COLLEGE_ADMIN, TRAINER and STUDENT — so all six returned
 * 403 to every caller, permanently. Guard on roles that exist.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AdminEnrollmentController {

    private final EnrollmentManagementService enrollmentService;

    /**
     * Get all enrollments for a batch
     * GET /api/v1/admin/batches/{batchId}/enrollments
     */
    @GetMapping("/batches/{batchId}/enrollments")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<BatchEnrollmentDTO> getBatchEnrollments(@PathVariable Long batchId) {
        log.info("Admin API: Get enrollments for batch {}", batchId);
        BatchEnrollmentDTO enrollments = enrollmentService.getBatchEnrollments(batchId);
        return ResponseEntity.ok(enrollments);
    }

    /**
     * Enroll a student in a batch
     * POST /api/v1/admin/batches/{batchId}/enrollments/{studentId}
     */
    @PostMapping("/batches/{batchId}/enrollments/{studentId}")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<EnrolledStudentDTO> enrollStudent(
            @PathVariable Long batchId,
            @PathVariable Long studentId) {
        log.info("Admin API: Enroll student {} in batch {}", studentId, batchId);
        EnrolledStudentDTO student = enrollmentService.enrollStudent(batchId, studentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(student);
    }

    /**
     * Remove a student from a batch
     * DELETE /api/v1/admin/batches/{batchId}/enrollments/{studentId}
     */
    @DeleteMapping("/batches/{batchId}/enrollments/{studentId}")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<Void> removeStudent(
            @PathVariable Long batchId,
            @PathVariable Long studentId) {
        log.info("Admin API: Remove student {} from batch {}", studentId, batchId);
        enrollmentService.removeStudent(batchId, studentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get all pending enrollment requests
     * GET /api/v1/admin/enrollment-requests/pending
     */
    @GetMapping("/enrollment-requests/pending")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<List<EnrollmentRequestDTO>> getPendingRequests() {
        log.info("Admin API: Get all pending enrollment requests");
        List<EnrollmentRequestDTO> requests = enrollmentService.getPendingRequests();
        return ResponseEntity.ok(requests);
    }

    /**
     * Approve an enrollment request
     * POST /api/v1/admin/enrollment-requests/{requestId}/approve
     */
    @PostMapping("/enrollment-requests/{requestId}/approve")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<EnrollmentRequestDTO> approveRequest(
            @PathVariable Long requestId,
            Authentication authentication) {
        log.info("Admin API: Approve request {}", requestId);
        // In real app, extract admin user ID from authentication
        Long adminUserId = 1L; // TODO: Extract from authentication
        EnrollmentRequestDTO request = enrollmentService.approveRequest(requestId, adminUserId);
        return ResponseEntity.ok(request);
    }

    /**
     * Reject an enrollment request
     * POST /api/v1/admin/enrollment-requests/{requestId}/reject
     */
    @PostMapping("/enrollment-requests/{requestId}/reject")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'SYSTEM_ADMIN')")
    public ResponseEntity<EnrollmentRequestDTO> rejectRequest(
            @PathVariable Long requestId,
            Authentication authentication) {
        log.info("Admin API: Reject request {}", requestId);
        // In real app, extract admin user ID from authentication
        Long adminUserId = 1L; // TODO: Extract from authentication
        EnrollmentRequestDTO request = enrollmentService.rejectRequest(requestId, adminUserId);
        return ResponseEntity.ok(request);
    }
}
