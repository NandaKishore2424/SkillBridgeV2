package com.skillbridge.enrollment.controller;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.enrollment.dto.CreateEnrollmentRequestDTO;
import com.skillbridge.enrollment.dto.EnrollmentRequestDTO;
import com.skillbridge.enrollment.service.EnrollmentManagementService;
import com.skillbridge.trainer.repository.TrainerRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.TrainerOnly;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Trainer enrollment requests: a trainer asks to add or remove a student, an
 * admin approves.
 *
 * <p>The trainer is resolved from the security principal. Both methods here
 * used a hard-coded {@code Long trainerId = 1L} with a {@code TODO}, so every
 * trainer in the system created requests attributed to trainer 1 and read
 * trainer 1's queue back — a trainer with no requests of their own saw someone
 * else's, and their own requests were filed under a colleague's name.
 */
@RestController
@RequestMapping("/api/v1/trainer")
@RequiredArgsConstructor
@Slf4j
public class TrainerEnrollmentController {

    private final EnrollmentManagementService enrollmentService;
    private final TrainerRepository trainerRepository;

    /**
     * Create an enrollment request
     * POST /api/v1/trainer/enrollment-requests
     */
    @PostMapping("/enrollment-requests")
    @TrainerOnly
    public ResponseEntity<EnrollmentRequestDTO> createRequest(
            @Valid @RequestBody CreateEnrollmentRequestDTO request,
            Authentication authentication) {
        log.info("Trainer API: Create enrollment request");
        EnrollmentRequestDTO enrollmentRequest =
                enrollmentService.createEnrollmentRequest(callingTrainerId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(enrollmentRequest);
    }

    /**
     * The calling trainer's own pending requests, newest first.
     * GET /api/v1/trainer/enrollment-requests
     */
    @GetMapping("/enrollment-requests")
    @TrainerOnly
    public ResponseEntity<PagedResponse<EnrollmentRequestDTO>> getMyRequests(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Trainer API: Get my enrollment requests");
        return ResponseEntity.ok(PagedResponse.from(enrollmentService.getTrainerRequests(
                callingTrainerId(authentication), Pagination.of(page, size))));
    }

    /**
     * The caller's {@code trainers.id}.
     *
     * <p>The token carries a {@code users.id}; every enrollment request is keyed
     * by trainer profile id, so the two have to be translated. A TRAINER role
     * with no trainer row is a provisioning fault rather than a client error,
     * but 404 is the honest answer to "show me my requests" when there is no
     * "me" to show.
     */
    private Long callingTrainerId(Authentication authentication) {
        AuthenticatedUser caller = SecurityUtils.requirePrincipal(authentication);
        return trainerRepository.findByUser_Id(caller.getId())
                .orElseThrow(() -> ResourceNotFoundException.of("Trainer", caller.getId()))
                .getId();
    }
}
