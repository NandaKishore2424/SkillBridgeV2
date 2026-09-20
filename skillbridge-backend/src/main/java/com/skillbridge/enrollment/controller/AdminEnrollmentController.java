package com.skillbridge.enrollment.controller;

import com.skillbridge.enrollment.dto.BatchEnrollmentDTO;
import com.skillbridge.enrollment.dto.EnrolledStudentDTO;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.enrollment.dto.EnrollmentRequestDTO;
import com.skillbridge.enrollment.service.EnrollmentManagementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.security.CollegeOrSystemAdmin;
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
public class AdminEnrollmentController {

    private final EnrollmentManagementService enrollmentService;

    /**
     * Get all enrollments for a batch
     * GET /api/v1/admin/batches/{batchId}/enrollments
     */
    @GetMapping("/batches/{batchId}/enrollments")
    @CollegeOrSystemAdmin
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
    @CollegeOrSystemAdmin
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
    @CollegeOrSystemAdmin
    public ResponseEntity<Void> removeStudent(
            @PathVariable Long batchId,
            @PathVariable Long studentId) {
        log.info("Admin API: Remove student {} from batch {}", studentId, batchId);
        enrollmentService.removeStudent(batchId, studentId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Pending enrollment requests, newest first.
     * GET /api/v1/admin/enrollment-requests/pending
     *
     * <p>Paged: the queue is one row per student per batch change, and it grows
     * with the college. For a SYSTEM_ADMIN it grows with the whole platform.
     */
    @GetMapping("/enrollment-requests/pending")
    @CollegeOrSystemAdmin
    public ResponseEntity<PagedResponse<EnrollmentRequestDTO>> getPendingRequests(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Admin API: Get pending enrollment requests");
        return ResponseEntity.ok(PagedResponse.from(
                enrollmentService.getPendingRequests(Pagination.of(page, size))));
    }

    /**
     * Approve an enrollment request.
     *
     * <p>POST /api/v1/admin/enrollment-requests/{requestId}/approve
     *
     * <p><b>The reviewer is the caller.</b> Both of these used to read
     * {@code Long adminUserId = 1L; // TODO: Extract from authentication}, which
     * did three things at once. It recorded every approval and rejection in
     * {@code enrollment_requests.reviewed_by} and in the audit log as user id 1
     * — for a system whose point is knowing who decided what. It made both
     * endpoints answer <b>404</b> on any database without a user id 1, because
     * {@code requireUser(1L)} misses: nothing was wrong on the developer's
     * restored copy, and approval was simply broken everywhere else. And
     * {@code rejectRequest} dropped the reason the admin typed, because the
     * two-argument overload has none to pass on.
     */
    @PostMapping("/enrollment-requests/{requestId}/approve")
    @CollegeOrSystemAdmin
    public ResponseEntity<EnrollmentRequestDTO> approveRequest(
            @PathVariable Long requestId,
            Authentication authentication) {
        Long adminUserId = SecurityUtils.requirePrincipal(authentication).getId();
        log.info("Admin API: Approve request {}", requestId);
        EnrollmentRequestDTO request = enrollmentService.approveRequest(requestId, adminUserId);
        return ResponseEntity.ok(request);
    }

    /**
     * Reject an enrollment request.
     *
     * <p>POST /api/v1/admin/enrollment-requests/{requestId}/reject
     *
     * <p>The body is optional: the frontend sends {@code {"reason": "..."}} when
     * the admin typed one and {@code {}} when they did not.
     */
    @PostMapping("/enrollment-requests/{requestId}/reject")
    @CollegeOrSystemAdmin
    public ResponseEntity<EnrollmentRequestDTO> rejectRequest(
            @PathVariable Long requestId,
            @RequestBody(required = false) RejectionRequest body,
            Authentication authentication) {
        Long adminUserId = SecurityUtils.requirePrincipal(authentication).getId();
        log.info("Admin API: Reject request {}", requestId);
        EnrollmentRequestDTO request = enrollmentService.rejectRequest(
                requestId, adminUserId, body == null ? null : body.reason());
        return ResponseEntity.ok(request);
    }

    /** Why the request was declined. The applicant will ask. */
    public record RejectionRequest(String reason) {
    }
}
