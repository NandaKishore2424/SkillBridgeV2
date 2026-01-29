package com.skillbridge.enrollment.controller;

import com.skillbridge.enrollment.dto.CreateEnrollmentRequestDTO;
import com.skillbridge.enrollment.dto.EnrollmentRequestDTO;
import com.skillbridge.enrollment.service.EnrollmentManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Trainer Enrollment Requests
 * Trainers can request to add/remove students (requires admin approval)
 */
@RestController
@RequestMapping("/api/v1/trainer")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class TrainerEnrollmentController {

    private final EnrollmentManagementService enrollmentService;

    /**
     * Create an enrollment request
     * POST /api/v1/trainer/enrollment-requests
     */
    @PostMapping("/enrollment-requests")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<EnrollmentRequestDTO> createRequest(
            @Valid @RequestBody CreateEnrollmentRequestDTO request,
            Authentication authentication) {
        log.info("Trainer API: Create enrollment request");
        // In real app, extract trainer ID from authentication
        Long trainerId = 1L; // TODO: Extract from authentication
        EnrollmentRequestDTO enrollmentRequest = enrollmentService.createEnrollmentRequest(trainerId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(enrollmentRequest);
    }

    /**
     * Get trainer's own enrollment requests
     * GET /api/v1/trainer/enrollment-requests
     */
    @GetMapping("/enrollment-requests")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<List<EnrollmentRequestDTO>> getMyRequests(Authentication authentication) {
        log.info("Trainer API: Get my enrollment requests");
        // In real app, extract trainer ID from authentication
        Long trainerId = 1L; // TODO: Extract from authentication
        List<EnrollmentRequestDTO> requests = enrollmentService.getTrainerRequests(trainerId);
        return ResponseEntity.ok(requests);
    }
}
