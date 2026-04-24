package com.skillbridge.trainer.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.trainer.dto.CreateTrainerRequest;
import com.skillbridge.trainer.dto.TrainerDTO;
import com.skillbridge.trainer.service.TrainerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/trainers")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class TrainerAdminController {
    private final TrainerService trainerService;

    @GetMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<TrainerDTO>> getAllTrainers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        Page<TrainerDTO> trainers = trainerService.getTrainersByCollege(user.getCollegeId(), PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.<TrainerDTO>builder()
                .items(trainers.getContent())
                .page(trainers.getNumber())
                .size(trainers.getSize())
                .totalElements(trainers.getTotalElements())
                .totalPages(trainers.getTotalPages())
                .build());
    }

    @PostMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<TrainerDTO> createTrainer(@RequestBody CreateTrainerRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
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
}
