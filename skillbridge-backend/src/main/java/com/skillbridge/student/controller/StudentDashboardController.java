package com.skillbridge.student.controller;

import com.skillbridge.auth.entity.User;
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

    /**
     * Get dashboard statistics for the logged-in student
     */
    @GetMapping("/dashboard/stats")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentDashboardStatsDTO> getDashboardStats() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
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
        User user = (User) auth.getPrincipal();
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
        User user = (User) auth.getPrincipal();
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
        User user = (User) auth.getPrincipal();
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
        User user = (User) auth.getPrincipal();
        log.info("Fetching batch {} details for student: {}", batchId, user.getEmail());

        StudentBatchDTO batch = dashboardService.getBatchDetails(user.getId(), batchId);
        return ResponseEntity.ok(batch);
    }

    /**
     * Apply to a batch
     */
    @PostMapping("/batches/apply")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<Map<String, Object>> applyToBatch(@RequestBody Map<String, Long> request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        Long batchId = request.get("batchId");

        log.info("Student {} applying to batch {}", user.getEmail(), batchId);

        Map<String, Object> result = dashboardService.applyToBatch(user.getId(), batchId);
        return ResponseEntity.ok(result);
    }

    /**
     * Get student progress for a specific batch
     */
    @GetMapping("/batches/{batchId}/progress")
    @PreAuthorize("hasRole('STUDENT')")
    public ResponseEntity<StudentProgressDTO> getBatchProgress(@PathVariable Long batchId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        log.info("Fetching progress for student {} in batch {}", user.getEmail(), batchId);

        StudentProgressDTO progress = dashboardService.getStudentProgress(user.getId(), batchId);
        return ResponseEntity.ok(progress);
    }
}
