package com.skillbridge.trainer.controller;

import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.trainer.dto.TrainerDashboardStatsDTO;
import com.skillbridge.trainer.dto.TrainerBatchDTO;
import com.skillbridge.trainer.dto.TrainerStudentDTO;
import com.skillbridge.trainer.service.TrainerDashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.TrainerOnly;
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
public class TrainerDashboardController {
    private final TrainerDashboardService dashboardService;

    @GetMapping("/dashboard/stats")
    @TrainerOnly
    public ResponseEntity<TrainerDashboardStatsDTO> getDashboardStats() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Getting dashboard stats for trainer: {}", user.getEmail());

        TrainerDashboardStatsDTO stats = dashboardService.getDashboardStats(user.getId());
        return ResponseEntity.ok(stats);
    }

    /**
     * The batches this trainer is assigned to, most recent intake first.
     *
     * <p>Paged: a trainer accumulates batches over their whole time at the
     * college.
     */
    @GetMapping("/batches")
    @TrainerOnly
    public ResponseEntity<PagedResponse<TrainerBatchDTO>> getTrainerBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Getting batches for trainer: {}", user.getEmail());

        return ResponseEntity.ok(PagedResponse.from(
                dashboardService.getTrainerBatches(user.getId(), Pagination.of(page, size))));
    }

    /**
     * Every student enrolled on one of this trainer's batches, by name.
     *
     * <p>Paged: this is the list that grows with class size, which is the whole
     * reason the endpoint was on the list.
     */
    @GetMapping("/batches/{batchId}/students")
    @TrainerOnly
    public ResponseEntity<PagedResponse<TrainerStudentDTO>> getBatchStudents(
            @PathVariable Long batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        log.info("Getting students for batch {} by trainer: {}", batchId, user.getEmail());

        return ResponseEntity.ok(PagedResponse.from(
                dashboardService.getBatchStudents(user.getId(), batchId, Pagination.of(page, size))));
    }
}
