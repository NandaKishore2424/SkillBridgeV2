package com.skillbridge.trainer.controller;

import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.trainer.dto.CreateTrainerRequest;
import com.skillbridge.trainer.dto.TrainerDTO;
import com.skillbridge.trainer.service.TrainerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import com.skillbridge.common.tenant.SoftDeleteService;
import jakarta.validation.Valid;
import com.skillbridge.trainer.dto.UpdateTrainerAdminRequest;
import com.skillbridge.batch.service.BatchAssignmentService;

import java.util.Map;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/admin/trainers")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class TrainerAdminController {
    private final TrainerService trainerService;
    private final SoftDeleteService softDeleteService;
    private final BatchAssignmentService batchAssignmentService;

    @GetMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<TrainerDTO>> getAllTrainers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        Page<TrainerDTO> trainers = trainerService.getTrainersByCollege(user.getCollegeId(), Pagination.of(page, size));
        return ResponseEntity.ok(PagedResponse.from(trainers));
    }

    @PostMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<TrainerDTO> createTrainer(@RequestBody CreateTrainerRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        request.setCollegeId(user.getCollegeId());
        TrainerDTO trainer = trainerService.createTrainer(request);
        return ResponseEntity.ok(trainer);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<TrainerDTO> getTrainerById(@PathVariable Long id) {
        TrainerDTO trainer = trainerService.getTrainerById(id);
        return ResponseEntity.ok(trainer);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Void> updateTrainerStatus(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> request) {
        trainerService.updateTrainerStatus(id, request.get("isActive"));
        return ResponseEntity.ok().build();
    }

    /**
     * Admin edit of a trainer's professional details.
     * PUT /api/v1/admin/trainers/{id}
     *
     * <p>Keyed by trainer id, unlike updateTrainerProfile which a trainer calls
     * for their own record by user id.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<TrainerDTO> updateTrainer(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTrainerAdminRequest request) {
        return ResponseEntity.ok(trainerService.updateTrainerAsAdmin(id, request));
    }

    /**
     * Assign this trainer to a batch, from the trainer's side.
     *
     * <p>The same {@code batch_trainers} row as
     * {@code POST /admin/batches/{id}/trainers}; the UI offers both directions
     * and only the batch side existed. Delegates so the tenant checks and the
     * join-table handling live in one place.
     */
    @PostMapping("/{id}/batches/{batchId}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> assignToBatch(@PathVariable Long id, @PathVariable Long batchId) {
        int count = batchAssignmentService.assignTrainer(batchId, id);
        return ResponseEntity.ok(Map.of("success", true, "trainerId", id,
                "batchId", batchId, "trainerCount", count));
    }

    @DeleteMapping("/{id}/batches/{batchId}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> unassignFromBatch(@PathVariable Long id, @PathVariable Long batchId) {
        int count = batchAssignmentService.unassignTrainer(batchId, id);
        return ResponseEntity.ok(Map.of("success", true, "trainerId", id,
                "batchId", batchId, "trainerCount", count));
    }

    /**
     * Soft-delete this trainer.
     *
     * <p>Marks {@code deleted_at} rather than removing the row: the audit
     * trail, enrollments and progress history all reference it, and a hard
     * delete would take them with it. Hidden from every read afterwards by the
     * {@code activeFilter}.
     *
     * <p>Refused with 409 TRAINER_HAS_BATCHES while the trainer is assigned to a live batch. Also deactivates the login.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Void> deleteTrainer(@PathVariable Long id, Authentication auth) {
        softDeleteService.deleteTrainer(id, SecurityUtils.requirePrincipal(auth).getId());
        return ResponseEntity.noContent().build();
    }
}
