package com.skillbridge.batch.controller;

import com.skillbridge.common.exception.ForbiddenException;
import com.skillbridge.common.idempotency.Idempotent;
import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.dto.BatchDTO;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.batch.service.BatchAssignmentService;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.common.tenant.TenantGuard;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.SortParameter;
import com.skillbridge.batch.repository.BatchSpecifications;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.company.dto.CompanyDTO;
import com.skillbridge.company.entity.Company;
import com.skillbridge.company.repository.CompanyRepository;
import com.skillbridge.trainer.dto.TrainerDTO;
import com.skillbridge.trainer.entity.Trainer;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import com.skillbridge.common.tenant.SoftDeleteService;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.HashMap;
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
public class BatchController {

    private final BatchRepository batchRepository;
    private final SoftDeleteService softDeleteService;
    private final CollegeRepository collegeRepository;
    private final BatchAssignmentService batchAssignmentService;
    private final CollegeAdminRepository collegeAdminRepository;
    private final TrainerRepository trainerRepository;
    private final CompanyRepository companyRepository;

    /**
     * Batch fields a client may sort by, mapped to entity paths.
     *
     * <p>Deliberately short. Every entry is a column the list screen actually
     * offers, and anything not here is a 400 rather than a 500 from deep inside
     * the query — see {@link SortParameter}.
     */
    private static final Map<String, String> SORTABLE = SortParameter.allow(
            "name", "name",
            "status", "status",
            "startDate", "startDate",
            "endDate", "endDate",
            "createdAt", "createdAt");

    /**
     * The college's batches, filtered and sorted server-side.
     *
     * <p>{@code search} and {@code status} are applied in SQL on purpose. The
     * list screen used to filter {@code items} client-side, which searched only
     * the page already on screen: a batch on page 3 was invisible to a search
     * run from page 1, and the empty state said "try adjusting your search
     * query". Filtering has to happen where the whole result set is.
     */
    @GetMapping
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<BatchDTO>> getAllBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort
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
            throw new ForbiddenException("This action requires an account scoped to a college.");
        }

        Specification<Batch> spec = Specification.allOf(
                BatchSpecifications.withCollege(),
                BatchSpecifications.inCollege(collegeId),
                BatchSpecifications.hasStatus(status),
                BatchSpecifications.matches(search));

        Page<Batch> batches = batchRepository.findAll(spec, Pagination.of(page, size,
                SortParameter.parse(sort, SORTABLE, Sort.by(Sort.Direction.DESC, "startDate"))));
        List<Long> ids = batches.getContent().stream().map(Batch::getId).toList();

        // All three counts in one round trip. They used to be three grouped
        // queries -- each bounded, but round trips are what a remote database
        // charges for.
        Map<Long, long[]> counts = associationCounts(ids);
        // Maps through the page rather than the already-built list, so the paging
        // metadata and the content cannot come from different page objects.
        return ResponseEntity.ok(PagedResponse.from(batches, b -> {
            long[] c = counts.getOrDefault(b.getId(), EMPTY_COUNTS);
            return convertToDTO(b, c[0], c[1], c[2]);
        }));
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
                .orElseThrow(() -> ResourceNotFoundException.of("Batch", id));
    }

    /**
     * Trainers assigned to this batch.
     *
     * <p>The batch is resolved and tenant-checked first, then the trainers are
     * queried as their own page. Loading the batch with its {@code trainers}
     * collection and paging that would page in memory -- Hibernate cannot push
     * LIMIT/OFFSET into a collection fetch.
     */
    @GetMapping("/{id}/trainers")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<TrainerDTO>> getBatchTrainers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Fetching trainers for batch: {}", id);
        if (!isVisibleBatch(id)) {
            throw ResourceNotFoundException.of("Batch", id);
        }
        return ResponseEntity.ok(PagedResponse.from(
                trainerRepository.findByBatch(id, Pagination.of(page, size)),
                this::convertTrainerToDTO));
    }

    @GetMapping("/{id}/companies")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<CompanyDTO>> getBatchCompanies(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Fetching companies for batch: {}", id);
        if (!isVisibleBatch(id)) {
            throw ResourceNotFoundException.of("Batch", id);
        }
        return ResponseEntity.ok(PagedResponse.from(
                companyRepository.findByBatch(id, Pagination.of(page, size)),
                this::convertCompanyToDTO));
    }

    // Idempotent: batches has no unique constraint of any kind, so a
    // double-clicked Create button leaves two identical rows with nothing to
    // tell them apart afterwards. Requires an Idempotency-Key header.
    @Idempotent
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
        Optional<Batch> batchOpt = findVisibleBatch(id);
        if (batchOpt.isEmpty()) {
            throw ResourceNotFoundException.of("Batch", id);
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
        Optional<Batch> batchOpt = findVisibleBatch(id);
        if (batchOpt.isEmpty()) {
            throw ResourceNotFoundException.of("Batch", id);
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
        int count = batchAssignmentService.replaceTrainers(id, request.trainerIds);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Trainers assigned successfully",
                "batchId", id,
                "trainerCount", count));
    }

    /**
     * Detach one trainer, leaving the rest in place.
     *
     * <p>The assign endpoint above replaces the whole set, which is what its
     * multi-select screen means but is the wrong tool for removing a single
     * person. The UI has had an unassign call since it was written; the endpoint
     * did not exist.
     */
    @DeleteMapping("/{id}/trainers/{trainerId}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> unassignTrainer(@PathVariable Long id, @PathVariable Long trainerId) {
        int count = batchAssignmentService.unassignTrainer(id, trainerId);
        return ResponseEntity.ok(Map.of(
                "success", true, "batchId", id, "trainerCount", count));
    }

    @PostMapping("/{id}/companies")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> assignCompanies(
            @PathVariable Long id,
            @RequestBody AssignCompaniesRequest request) {
        int count = batchAssignmentService.replaceCompanies(id, request.companyIds);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Companies linked successfully",
                "batchId", id,
                "companyCount", count));
    }

    @DeleteMapping("/{id}/companies/{companyId}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> unassignCompany(@PathVariable Long id, @PathVariable Long companyId) {
        int count = batchAssignmentService.unassignCompany(id, companyId);
        return ResponseEntity.ok(Map.of(
                "success", true, "batchId", id, "companyCount", count));
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

    private static final long[] EMPTY_COUNTS = {0L, 0L, 0L};

    /** {@code batchId -> [trainers, companies, students]} for a page. */
    private Map<Long, long[]> associationCounts(List<Long> batchIds) {
        if (batchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, long[]> counts = new HashMap<>();
        for (Object[] row : batchRepository.countAssociationsByBatchIds(batchIds)) {
            counts.put(((Number) row[0]).longValue(), new long[]{
                    ((Number) row[1]).longValue(),
                    ((Number) row[2]).longValue(),
                    ((Number) row[3]).longValue()});
        }
        return counts;
    }

    /**
     * Does this batch exist and belong to the caller's college?
     *
     * <p>A batch in another college and a batch that does not exist must be
     * indistinguishable from outside, so both answer false and both become 404.
     */
    private boolean isVisibleBatch(Long id) {
        return findVisibleBatch(id).isPresent();
    }

    /**
     * The batch, if it exists AND belongs to the caller's college; empty
     * otherwise, so the two cases answer the same 404. findById alone does not
     * check the college (a Hibernate filter never applies to a load by id), and
     * that let a college admin rename and re-status another college's batch
     * until CrossTenantAccessTest caught it.
     */
    private Optional<Batch> findVisibleBatch(Long id) {
        return batchRepository.findByIdWithCollege(id)
                .filter(b -> TenantGuard.isVisible(b.getCollege().getId()));
    }

    /**
     * The real mapper, now on {@link BatchDTO} so the SYSTEM_ADMIN's per-college
     * batch list maps identically. {@code studentCount} was hardcoded 0 with a
     * TODO before; the batch detail page reads it for its "Enrollments (n)" tab
     * label, so the placeholder was visible.
     */
    private BatchDTO convertToDTO(Batch batch, long trainerCount, long companyCount, long studentCount) {
        return BatchDTO.from(batch, trainerCount, companyCount, studentCount);
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

    /**
     * Soft-delete this batch.
     *
     * <p>Marks {@code deleted_at} rather than removing the row: the audit
     * trail, enrollments and progress history all reference it, and a hard
     * delete would take them with it. Hidden from every read afterwards by the
     * {@code activeFilter}.
     *
     * <p>Refused with 409 BATCH_HAS_ENROLLMENTS if students are actively enrolled and the batch is not COMPLETED.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Void> deleteBatch(@PathVariable Long id, Authentication auth) {
        softDeleteService.deleteBatch(id, SecurityUtils.requirePrincipal(auth).getId());
        return ResponseEntity.noContent().build();
    }
}
