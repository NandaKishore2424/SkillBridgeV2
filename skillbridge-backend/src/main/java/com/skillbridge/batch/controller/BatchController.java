package com.skillbridge.batch.controller;

import com.skillbridge.common.idempotency.Idempotent;
import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.dto.BatchDTO;
import com.skillbridge.batch.dto.BatchStatusRequest;
import com.skillbridge.batch.dto.CreateBatchRequest;
import com.skillbridge.batch.dto.UpdateBatchRequest;
import com.skillbridge.batch.service.BatchService;
import jakarta.validation.Valid;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.batch.service.BatchAssignmentService;
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
import com.skillbridge.common.security.CollegeAdminOnly;
import com.skillbridge.common.security.TrainerOrCollegeAdmin;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import com.skillbridge.common.tenant.SoftDeleteService;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.exception.UnauthorizedException;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/admin/batches")
@RequiredArgsConstructor
@Slf4j
public class BatchController {

    private final BatchService batchService;
    private final BatchRepository batchRepository;
    private final SoftDeleteService softDeleteService;
    private final BatchAssignmentService batchAssignmentService;
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
    @CollegeAdminOnly
    public ResponseEntity<PagedResponse<BatchDTO>> getAllBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort
    ) {
        log.info("Fetching all batches for college admin");
        // 403 for an account with no college. The college_admins fallback that
        // used to be here is gone: every college admin has users.college_id
        // (measured 2026-09-19).
        Long collegeId = SecurityUtils.requireCollegeId();

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
    @TrainerOrCollegeAdmin
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
    @CollegeAdminOnly
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
    @CollegeAdminOnly
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
    @CollegeAdminOnly
    public ResponseEntity<BatchDTO> createBatch(@Valid @RequestBody CreateBatchRequest request) {
        Batch batch = batchService.create(request, SecurityUtils.requireCollegeId());
        log.info("Created batch {}", batch.getId());
        return ResponseEntity.ok(convertToDTO(batch));
    }

    @PutMapping("/{id}")
    @CollegeAdminOnly
    public ResponseEntity<BatchDTO> updateBatch(@PathVariable Long id, @Valid @RequestBody UpdateBatchRequest request) {
        return ResponseEntity.ok(convertToDTO(batchService.update(id, request)));
    }

    @PatchMapping("/{id}/status")
    @CollegeAdminOnly
    public ResponseEntity<BatchDTO> updateBatchStatus(@PathVariable Long id,
                                                      @Valid @RequestBody BatchStatusRequest request) {
        return ResponseEntity.ok(convertToDTO(batchService.updateStatus(id, request.status)));
    }

    @PostMapping("/{id}/trainers")
    @CollegeAdminOnly
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
    @CollegeAdminOnly
    public ResponseEntity<?> unassignTrainer(@PathVariable Long id, @PathVariable Long trainerId) {
        int count = batchAssignmentService.unassignTrainer(id, trainerId);
        return ResponseEntity.ok(Map.of(
                "success", true, "batchId", id, "trainerCount", count));
    }

    @PostMapping("/{id}/companies")
    @CollegeAdminOnly
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
    @CollegeAdminOnly
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
    @CollegeAdminOnly
    public ResponseEntity<Void> deleteBatch(@PathVariable Long id, Authentication auth) {
        softDeleteService.deleteBatch(id, SecurityUtils.requirePrincipal(auth).getId());
        return ResponseEntity.noContent().build();
    }
}
