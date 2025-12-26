package com.skillbridge.batch.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.dto.BatchDTO;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.college.repository.CollegeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/admin/batches")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class BatchController {

    private final BatchRepository batchRepository;
    private final CollegeRepository collegeRepository;
    private final CollegeAdminRepository collegeAdminRepository;

    @GetMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<List<BatchDTO>> getAllBatches() {
        log.info("Fetching all batches for college admin");
        // Get college ID from authenticated user
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        Long collegeId = user.getCollegeId();

        // If collegeId is null, try to get it from CollegeAdmin entity
        if (collegeId == null) {
            Optional<CollegeAdmin> collegeAdminOpt = collegeAdminRepository.findByUserId(user.getId());
            if (collegeAdminOpt.isPresent()) {
                collegeId = collegeAdminOpt.get().getCollege().getId();
            }
        }

        if (collegeId == null) {
            return ResponseEntity.badRequest().build();
        }

        List<Batch> batches = batchRepository.findByCollegeId(collegeId);
        List<BatchDTO> batchDTOs = batches.stream()
                .map(this::convertToDTO)
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(batchDTOs);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<BatchDTO> getBatchById(@PathVariable Long id) {
        log.info("Fetching batch with id: {}", id);
        Optional<Batch> batch = batchRepository.findById(id);
        return batch.map(b -> ResponseEntity.ok(convertToDTO(b)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Batch> createBatch(@RequestBody CreateBatchRequest request) {
        log.info("Creating batch: {}", request.name);

        try {
            // Get college ID from authenticated user
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            log.debug("Authentication principal type: {}", auth.getPrincipal().getClass().getName());

            if (!(auth.getPrincipal() instanceof User)) {
                log.error("Principal is not a User instance: {}", auth.getPrincipal());
                throw new RuntimeException("Authentication error: Invalid user principal");
            }

            User user = (User) auth.getPrincipal();
            Long collegeId = user.getCollegeId();

            // If collegeId is null, try to get it from CollegeAdmin entity
            if (collegeId == null) {
                log.warn("User {} does not have collegeId in User entity, checking CollegeAdmin", user.getEmail());
                Optional<CollegeAdmin> collegeAdminOpt = collegeAdminRepository.findByUserId(user.getId());
                if (collegeAdminOpt.isPresent()) {
                    collegeId = collegeAdminOpt.get().getCollege().getId();
                    log.info("Found collegeId from CollegeAdmin: {}", collegeId);
                }
            }

            log.info("User: {}, College ID: {}", user.getEmail(), collegeId);

            if (collegeId == null) {
                log.error("User {} does not have a collegeId", user.getEmail());
                throw new RuntimeException("User does not have a college assigned");
            }

            // Make final for lambda expression
            final Long finalCollegeId = collegeId;

            // Verify college exists
            var college = collegeRepository.findById(finalCollegeId)
                    .orElseThrow(() -> new RuntimeException("College not found with id: " + finalCollegeId));

            // Parse dates from strings if provided
            LocalDate startDate = null;
            LocalDate endDate = null;

            if (request.startDate != null && !request.startDate.isEmpty()) {
                try {
                    // Try ISO format first (YYYY-MM-DD)
                    startDate = LocalDate.parse(request.startDate);
                    log.debug("Parsed start date: {}", startDate);
                } catch (Exception e) {
                    try {
                        // Try MM/DD/YYYY format
                        startDate = LocalDate.parse(request.startDate, DateTimeFormatter.ofPattern("MM/dd/yyyy"));
                        log.debug("Parsed start date (MM/dd/yyyy): {}", startDate);
                    } catch (Exception e2) {
                        log.warn("Failed to parse start date: {}", request.startDate);
                    }
                }
            }

            if (request.endDate != null && !request.endDate.isEmpty()) {
                try {
                    endDate = LocalDate.parse(request.endDate);
                    log.debug("Parsed end date: {}", endDate);
                } catch (Exception e) {
                    try {
                        endDate = LocalDate.parse(request.endDate, DateTimeFormatter.ofPattern("MM/dd/yyyy"));
                        log.debug("Parsed end date (MM/dd/yyyy): {}", endDate);
                    } catch (Exception e2) {
                        log.warn("Failed to parse end date: {}", request.endDate);
                    }
                }
            }

            Batch batch = Batch.builder()
                    .college(college)
                    .name(request.name)
                    .description(request.description)
                    .status(request.status != null ? request.status : "UPCOMING")
                    .startDate(startDate)
                    .endDate(endDate)
                    .build();

            Batch savedBatch = batchRepository.save(batch);
            log.info("Successfully created batch with id: {}", savedBatch.getId());
            return ResponseEntity.ok(savedBatch);
        } catch (RuntimeException e) {
            log.error("Failed to create batch: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error creating batch: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create batch: " + e.getMessage(), e);
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Batch> updateBatch(@PathVariable Long id, @RequestBody CreateBatchRequest request) {
        log.info("Updating batch with id: {}", id);
        Optional<Batch> batchOpt = batchRepository.findById(id);
        if (batchOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Batch batch = batchOpt.get();
        batch.setName(request.name);
        batch.setDescription(request.description);
        if (request.status != null) {
            batch.setStatus(request.status);
        }

        // Parse dates
        if (request.startDate != null && !request.startDate.isEmpty()) {
            try {
                batch.setStartDate(LocalDate.parse(request.startDate));
            } catch (Exception e) {
                try {
                    batch.setStartDate(LocalDate.parse(request.startDate, DateTimeFormatter.ofPattern("MM/dd/yyyy")));
                } catch (Exception e2) {
                    log.warn("Failed to parse start date: {}", request.startDate);
                }
            }
        }

        if (request.endDate != null && !request.endDate.isEmpty()) {
            try {
                batch.setEndDate(LocalDate.parse(request.endDate));
            } catch (Exception e) {
                try {
                    batch.setEndDate(LocalDate.parse(request.endDate, DateTimeFormatter.ofPattern("MM/dd/yyyy")));
                } catch (Exception e2) {
                    log.warn("Failed to parse end date: {}", request.endDate);
                }
            }
        }

        Batch updatedBatch = batchRepository.save(batch);
        return ResponseEntity.ok(updatedBatch);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Batch> updateBatchStatus(
            @PathVariable Long id,
            @RequestBody StatusUpdateRequest request) {
        log.info("Updating batch status for id: {} to {}", id, request.status);
        Optional<Batch> batchOpt = batchRepository.findById(id);
        if (batchOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Batch batch = batchOpt.get();
        batch.setStatus(request.status);
        Batch updatedBatch = batchRepository.save(batch);
        return ResponseEntity.ok(updatedBatch);
    }

    // Helper method to convert Batch entity to DTO
    private BatchDTO convertToDTO(Batch batch) {
        return BatchDTO.builder()
                .id(batch.getId())
                .collegeId(batch.getCollege().getId())
                .collegeName(batch.getCollege().getName())
                .name(batch.getName())
                .description(batch.getDescription())
                .status(batch.getStatus())
                .startDate(batch.getStartDate())
                .endDate(batch.getEndDate())
                .createdAt(batch.getCreatedAt())
                .updatedAt(batch.getUpdatedAt())
                .build();
    }

    // DTOs
    public static class CreateBatchRequest {
        public String name;
        public String description;
        public String status;
        public String startDate; // Accept as string, parse in controller
        public String endDate; // Accept as string, parse in controller
        public Integer maxEnrollments; // Ignored for now (not in DB schema)
    }

    public static class StatusUpdateRequest {
        public String status;
    }
}
