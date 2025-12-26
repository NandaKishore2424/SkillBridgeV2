package com.skillbridge.trainer.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.trainer.dto.TrainerDTO;
import com.skillbridge.trainer.dto.UpdateTrainerProfileRequest;
import com.skillbridge.trainer.service.TrainerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/trainers")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class TrainerController {
    private final TrainerService trainerService;

    @GetMapping("/me")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<TrainerDTO> getMyProfile() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        TrainerDTO trainer = trainerService.getTrainerProfile(user.getId());
        return ResponseEntity.ok(trainer);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN') or hasRole('TRAINER') or hasRole('STUDENT')")
    public ResponseEntity<TrainerDTO> getTrainerById(@PathVariable Long id) {
        TrainerDTO trainer = trainerService.getTrainerById(id);
        return ResponseEntity.ok(trainer);
    }

    @PutMapping("/me")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<TrainerDTO> updateMyProfile(@RequestBody UpdateTrainerProfileRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        TrainerDTO updated = trainerService.updateTrainerProfile(user.getId(), request);
        return ResponseEntity.ok(updated);
    }
}
