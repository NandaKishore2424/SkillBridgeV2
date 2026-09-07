package com.skillbridge.student.controller;

import com.skillbridge.batch.dto.BatchDTO;
import com.skillbridge.student.dto.StudentDashboardStatsDTO;
import com.skillbridge.student.dto.RecommendedBatchDTO;
import com.skillbridge.student.dto.StudentBatchDTO;
import com.skillbridge.student.dto.StudentProgressDTO;
import com.skillbridge.student.service.StudentDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.common.exception.BadRequestException;
import com.skillbridge.student.dto.BatchApplicationDTO;
import com.skillbridge.student.service.StudentEnrollmentService;
import org.springframework.http.HttpStatus;

/**
 * Student Dashboard Controller
 * Handles student-specific dashboard operations using /api/v1/student/
 * endpoints
 */
@RestController
@RequestMapping("/api/v1/student")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class StudentDashboardController {

    private final StudentDashboardService dashboardService;
    private final StudentEnrollmentService enrollmentService;

    /**
     * Get dashboard statistics for the logged-in student
     */
    @GetMapping("/dashboard/stats")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentDashboardStatsDTO> getDashboardStats() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Fetching dashboard stats for student: {}", user.getEmail());

        StudentDashboardStatsDTO stats = dashboardService.getDashboardStats(user.getId());
        return ResponseEntity.ok(stats);
    }

    /**
     * Get recommended batches for the logged-in student
     */
    @GetMapping("/batches/recommended")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<List<RecommendedBatchDTO>> getRecommendedBatches() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Fetching recommended batches for student: {}", user.getEmail());

        List<RecommendedBatchDTO> batches = dashboardService.getRecommendedBatches(user.getId());
        return ResponseEntity.ok(batches);
    }

    /**
     * Get all available batches for enrollment
     */
    @GetMapping("/batches/available")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<List<BatchDTO>> getAvailableBatches() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Fetching available batches for student: {}", user.getEmail());

        List<BatchDTO> batches = dashboardService.getAvailableBatches(user.getCollegeId());
        return ResponseEntity.ok(batches);
    }

    /**
     * Get batches the student is enrolled in
     */
    @GetMapping("/batches")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<List<StudentBatchDTO>> getMyBatches() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Fetching enrolled batches for student: {}", user.getEmail());

        List<StudentBatchDTO> batches = dashboardService.getStudentBatches(user.getId());
        return ResponseEntity.ok(batches);
    }

    /**
     * Get details of a specific batch
     */
    @GetMapping("/batches/{batchId}")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentBatchDTO> getBatchDetails(@PathVariable Long batchId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Fetching batch {} details for student: {}", batchId, user.getEmail());

        StudentBatchDTO batch = dashboardService.getBatchDetails(user.getId(), batchId);
        return ResponseEntity.ok(batch);
    }

    /**
     * Apply to a batch.
     *
     * <p>Idempotent: re-applying returns the existing application with
     * {@code duplicate: true} rather than creating a second one or failing, so a
     * double-clicked button and a network retry both do the right thing.
     */
    @PostMapping("/batches/apply")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<BatchApplicationDTO> applyToBatch(@RequestBody Map<String, Long> request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);

        Long batchId = request.get("batchId");
        if (batchId == null) {
            throw new BadRequestException("batchId is required");
        }

        log.info("Student {} applying to batch {}", user.getEmail(), batchId);

        BatchApplicationDTO result = enrollmentService.applyToBatch(user.getId(), batchId);
        return ResponseEntity.status(result.isDuplicate() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(result);
    }

    /**
     * The signed-in student's applications, newest first.
     *
     * <p>Paged: a student accumulates an application row per batch they ever
     * applied to, and nothing prunes the rejected or withdrawn ones.
     */
    @GetMapping("/applications")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<PagedResponse<BatchApplicationDTO>> getMyApplications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        AuthenticatedUser user = SecurityUtils.currentUser();
        return ResponseEntity.ok(PagedResponse.from(
                enrollmentService.getMyApplications(user.getId(), Pagination.of(page, size))));
    }

    /**
     * Withdraw an application that has not been reviewed yet.
     */
    @PostMapping("/applications/{applicationId}/withdraw")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<BatchApplicationDTO> withdrawApplication(@PathVariable Long applicationId) {
        AuthenticatedUser user = SecurityUtils.currentUser();
        return ResponseEntity.ok(enrollmentService.withdrawApplication(user.getId(), applicationId));
    }

    /**
     * Get student progress for a specific batch
     */
    @GetMapping("/batches/{batchId}/progress")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentProgressDTO> getBatchProgress(@PathVariable Long batchId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Fetching progress for student {} in batch {}", user.getEmail(), batchId);

        StudentProgressDTO progress = dashboardService.getStudentProgress(user.getId(), batchId);
        return ResponseEntity.ok(progress);
    }
}
