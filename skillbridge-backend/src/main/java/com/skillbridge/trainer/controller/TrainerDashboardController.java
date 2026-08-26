package com.skillbridge.trainer.controller;

import com.skillbridge.trainer.dto.TrainerDashboardStatsDTO;
import com.skillbridge.trainer.dto.TrainerBatchDTO;
import com.skillbridge.trainer.dto.TrainerStudentDTO;
import com.skillbridge.trainer.service.TrainerDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/trainer")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class TrainerDashboardController {
    private final TrainerDashboardService dashboardService;

    @GetMapping("/dashboard/stats")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<TrainerDashboardStatsDTO> getDashboardStats() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Getting dashboard stats for trainer: {}", user.getEmail());

        TrainerDashboardStatsDTO stats = dashboardService.getDashboardStats(user.getId());
        return ResponseEntity.ok(stats);
    }

    @GetMapping("/batches")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<List<TrainerBatchDTO>> getTrainerBatches() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Getting batches for trainer: {}", user.getEmail());

        List<TrainerBatchDTO> batches = dashboardService.getTrainerBatches(user.getId());
        return ResponseEntity.ok(batches);
    }

    @GetMapping("/batches/{batchId}/students")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<List<TrainerStudentDTO>> getBatchStudents(@PathVariable Long batchId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Getting students for batch {} by trainer: {}", batchId, user.getEmail());

        List<TrainerStudentDTO> students = dashboardService.getBatchStudents(user.getId(), batchId);
        return ResponseEntity.ok(students);
    }
}
