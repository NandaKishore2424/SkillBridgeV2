package com.skillbridge.trainer.controller;

import com.skillbridge.trainer.dto.TrainerDTO;
import com.skillbridge.trainer.dto.UpdateTrainerProfileRequest;
import com.skillbridge.trainer.service.TrainerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.TrainerOnly;
import com.skillbridge.common.security.TrainerOrCollegeAdmin;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.api.DeprecatedEndpoint;

@RestController
@RequestMapping("/api/v1/trainers")
@RequiredArgsConstructor
@Slf4j
public class TrainerController {
    private final TrainerService trainerService;

    @GetMapping("/me")
    @TrainerOnly
    public ResponseEntity<TrainerDTO> getMyProfile() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        TrainerDTO trainer = trainerService.getTrainerProfile(user.getId());
        return ResponseEntity.ok(trainer);
    }

    /**
     * A trainer by id.
     *
     * <p>STUDENT removed from the guard for the same reason as
     * {@code GET /students/{id}}: TrainerDTO carries the email address, and a
     * tenant check alone does not stop id-walking within a college. Students
     * see the trainers on their own batches through the batch endpoints, which
     * are scoped to batches they are enrolled in.
     */
    @GetMapping("/{id}")
    @TrainerOrCollegeAdmin
    @DeprecatedEndpoint(
            since = "2026-09-06",
            sunset = "2026-12-31",
            replacement = "/api/v1/admin/trainers/{id}",
            reason = "Duplicate of the admin endpoint.")
    public ResponseEntity<TrainerDTO> getTrainerById(@PathVariable Long id) {
        TrainerDTO trainer = trainerService.getTrainerById(id);
        return ResponseEntity.ok(trainer);
    }

    @PutMapping("/me")
    @TrainerOnly
    public ResponseEntity<TrainerDTO> updateMyProfile(@RequestBody UpdateTrainerProfileRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        TrainerDTO updated = trainerService.updateTrainerProfile(user.getId(), request);
        return ResponseEntity.ok(updated);
    }
}
