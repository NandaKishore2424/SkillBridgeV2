package com.skillbridge.batch.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.dto.BatchDTO;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.common.tenant.TenantGuard;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.company.dto.CompanyDTO;
import com.skillbridge.company.entity.Company;
import com.skillbridge.company.repository.CompanyRepository;
import com.skillbridge.trainer.dto.TrainerDTO;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.skillbridge.common.exception.BusinessRuleException;
import com.skillbridge.common.exception.InternalServerException;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.exception.UnauthorizedException;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/admin/batches")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class BatchController {

    private final BatchRepository batchRepository;
    private final CollegeRepository collegeRepository;
    private final CollegeAdminRepository collegeAdminRepository;
    private final TrainerRepository trainerRepository;
    private final CompanyRepository companyRepository;

    @GetMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<BatchDTO>> getAllBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("Fetching all batches for college admin");
        // Get college ID from authenticated user
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
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

        Page<Batch> batches = batchRepository.findByCollegeIdWithCollege(collegeId, PageRequest.of(page, size));
        List<Long> ids = batches.getContent().stream().map(Batch::getId).toList();
        Map<Long, Long> trainerCounts = countsByBatchId(batchRepository.countTrainersByBatchIds(ids));
        Map<Long, Long> companyCounts = countsByBatchId(batchRepository.countCompaniesByBatchIds(ids));
        Map<Long, Long> studentCounts = countsByBatchId(batchRepository.countEnrollmentsByBatchIds(ids));
        List<BatchDTO> batchDTOs = batches.getContent().stream()
                .map(b -> convertToDTO(b,
                        trainerCounts.getOrDefault(b.getId(), 0L),
                        companyCounts.getOrDefault(b.getId(), 0L),
                        studentCounts.getOrDefault(b.getId(), 0L)))
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(PagedResponse.<BatchDTO>builder()
            .items(batchDTOs)
            .page(batches.getNumber())
            .size(batches.getSize())
            .totalElements(batches.getTotalElements())
            .totalPages(batches.getTotalPages())
            .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'TRAINER')")
    public ResponseEntity<BatchDTO> getBatchById(@PathVariable Long id) {
        log.info("Fetching batch with id: {}", id);
        // Filtered, not checked after the fact: a batch in another college and a
        // batch that does not exist must be indistinguishable from out here.
        Optional<Batch> batch = batchRepository.findByIdWithCollege(id)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()));
        return batch.map(b -> ResponseEntity.ok(convertToDTO(b)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/trainers")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<List<TrainerDTO>> getBatchTrainers(@PathVariable Long id) {
        log.info("Fetching trainers for batch: {}", id);
        Optional<Batch> batchOpt = batchRepository.findByIdWithTrainers(id)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()));
        if (batchOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<TrainerDTO> trainers = batchOpt.get().getTrainers().stream()
                .map(this::convertTrainerToDTO)
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(trainers);
    }

    @GetMapping("/{id}/companies")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<List<CompanyDTO>> getBatchCompanies(@PathVariable Long id) {
        log.info("Fetching companies for batch: {}", id);
        Optional<Batch> batchOpt = batchRepository.findByIdWithCompanies(id)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()));
        if (batchOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<CompanyDTO> companies = batchOpt.get().getCompanies().stream()
                .map(this::convertCompanyToDTO)
                .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.ok(companies);
    }

    @PostMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<BatchDTO> createBatch(@RequestBody CreateBatchRequest request) {
        log.info("Creating batch: {}", request.name);

        try {
            // Get college ID from authenticated user
            // requirePrincipal already raises 401 for a missing or unexpected
            // principal, so the hand-rolled instanceof check that used to live
            // here (and logged the whole principal object) is redundant.
            AuthenticatedUser user = SecurityUtils.currentUser();
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
                throw new BusinessRuleException("User does not have a college assigned");
            }

            // Make final for lambda expression
            final Long finalCollegeId = collegeId;

            // Verify college exists
            var college = collegeRepository.findById(finalCollegeId)
                    .orElseThrow(() -> new ResourceNotFoundException("College not found with id: " + finalCollegeId));

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
            return ResponseEntity.ok(convertToDTO(savedBatch));
        } catch (RuntimeException e) {
            log.error("Failed to create batch: {}", e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("Unexpected error creating batch: {}", e.getMessage(), e);
            throw new InternalServerException("Failed to create batch: " + e.getMessage(), e);
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<BatchDTO> updateBatch(@PathVariable Long id, @RequestBody CreateBatchRequest request) {
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
        return ResponseEntity.ok(convertToDTO(updatedBatch));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<BatchDTO> updateBatchStatus(
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
        return ResponseEntity.ok(convertToDTO(updatedBatch));
    }

    @PostMapping("/{id}/trainers")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> assignTrainers(
            @PathVariable Long id,
            @RequestBody AssignTrainersRequest request) {
        log.info("Assigning trainers to batch {}: {}", id, request.trainerIds);

        try {
            Optional<Batch> batchOpt = batchRepository.findById(id);
            if (batchOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Batch batch = batchOpt.get();
            batch.getTrainers().clear(); // Clear existing trainers

            // Add new trainers
            for (Long trainerId : request.trainerIds) {
                trainerRepository.findById(trainerId).ifPresent(trainer -> {
                    batch.getTrainers().add(trainer);
                });
            }

            Batch updatedBatch = batchRepository.save(batch);
            log.info("Successfully assigned {} trainers to batch {}", updatedBatch.getTrainers().size(), id);
            // Return simple Map to avoid Jackson serialization errors with lazy-loaded
            // entities
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Trainers assigned successfully",
                    "batchId", id,
                    "trainerCount", updatedBatch.getTrainers().size()));
        } catch (Exception e) {
            log.error("Error assigning trainers to batch {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "An unexpected error occurred"));
        }
    }

    @PostMapping("/{id}/companies")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> assignCompanies(
            @PathVariable Long id,
            @RequestBody AssignCompaniesRequest request) {
        log.info("Assigning companies to batch {}: {}", id, request.companyIds);

        try {
            Optional<Batch> batchOpt = batchRepository.findById(id);
            if (batchOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Batch batch = batchOpt.get();
            batch.getCompanies().clear(); // Clear existing companies

            // Add new companies
            for (Long companyId : request.companyIds) {
                companyRepository.findById(companyId).ifPresent(company -> {
                    batch.getCompanies().add(company);
                });
            }

            Batch updatedBatch = batchRepository.save(batch);
            log.info("Successfully assigned {} companies to batch {}", updatedBatch.getCompanies().size(), id);

            // Return simple Map to avoid Jackson serialization errors
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Companies assigned successfully",
                    "batchId", id,
                    "companyCount", updatedBatch.getCompanies().size()));
        } catch (Exception e) {
            log.error("Error assigning companies to batch {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", "An unexpected error occurred"));
        }
    }

    /** Turns {@code [batchId, count]} rows from a grouped count query into a lookup. */
    private static Map<Long, Long> countsByBatchId(List<Object[]> rows) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> (Long) r[0], r -> (Long) r[1]));
    }

    /**
     * Single-batch mapping, for the write paths where there is no page to
     * aggregate over.
     *
     * <p>Re-reads through {@code findByIdWithCollege} rather than mapping the
     * entity it was handed: after {@code save} the {@code college} may still be
     * an uninitialised proxy, and with {@code open-in-view: false} reading its
     * name would throw once the session closed.
     */
    private BatchDTO convertToDTO(Batch batch) {
        Batch loaded = batchRepository.findByIdWithCollege(batch.getId()).orElse(batch);
        List<Long> ids = List.of(loaded.getId());
        return convertToDTO(loaded,
                countsByBatchId(batchRepository.countTrainersByBatchIds(ids)).getOrDefault(loaded.getId(), 0L),
                countsByBatchId(batchRepository.countCompaniesByBatchIds(ids)).getOrDefault(loaded.getId(), 0L),
                countsByBatchId(batchRepository.countEnrollmentsByBatchIds(ids)).getOrDefault(loaded.getId(), 0L));
    }

    /**
     * The real mapper. Touches nothing lazy: {@code college} must already be
     * fetch-joined and both counts are supplied by the caller, so this cannot
     * throw {@code LazyInitializationException} however it is reached.
     */
    private BatchDTO convertToDTO(Batch batch, long trainerCount, long companyCount, long studentCount) {
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
                .trainerCount((int) trainerCount)
                .companyCount((int) companyCount)
                // Was hardcoded 0 with a TODO. The batch detail page reads it for
                // its "Enrollments (n)" tab label, so the placeholder was visible.
                .studentCount((int) studentCount)
                .build();
    }

    // Helper method to convert Trainer entity to DTO
    private TrainerDTO convertTrainerToDTO(Trainer trainer) {
        return TrainerDTO.builder()
                .id(trainer.getId())
                .userId(trainer.getUser() != null ? trainer.getUser().getId() : null)
                .email(trainer.getUser() != null ? trainer.getUser().getEmail() : null)
                .isActive(trainer.getUser() != null ? trainer.getUser().getIsActive() : null)
                .fullName(trainer.getFullName())
                .phone(trainer.getPhone())
                .department(trainer.getDepartment())
                .specialization(trainer.getSpecialization())
                .bio(trainer.getBio())
                .linkedinUrl(trainer.getLinkedinUrl())
                .yearsOfExperience(trainer.getYearsOfExperience())
                .createdAt(trainer.getCreatedAt())
                .updatedAt(trainer.getUpdatedAt())
                .build();
    }

    // Helper method to convert Company entity to DTO
    private CompanyDTO convertCompanyToDTO(Company company) {
        return CompanyDTO.builder()
                .id(company.getId())
                .collegeId(company.getCollege() != null ? company.getCollege().getId() : null)
                .collegeName(company.getCollege() != null ? company.getCollege().getName() : null)
                .name(company.getName())
                .domain(company.getDomain())
                .hiringType(company.getHiringType())
                .createdAt(company.getCreatedAt())
                .updatedAt(company.getUpdatedAt())
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

    public static class AssignTrainersRequest {
        public List<Long> trainerIds;
    }

    public static class AssignCompaniesRequest {
        public List<Long> companyIds;
    }
}
