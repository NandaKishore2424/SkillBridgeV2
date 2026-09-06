package com.skillbridge.company.controller;

import com.skillbridge.college.entity.College;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.common.tenant.TenantGuard;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.batch.service.BatchAssignmentService;
import com.skillbridge.common.dto.IdGrouping;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.company.dto.CompanyDTO;
import com.skillbridge.company.entity.Company;
import com.skillbridge.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import com.skillbridge.common.tenant.SoftDeleteService;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/admin/companies")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class CompanyController {

    private final CompanyRepository companyRepository;
    private final SoftDeleteService softDeleteService;
    private final CollegeRepository collegeRepository;
    private final BatchRepository batchRepository;
    private final BatchAssignmentService batchAssignmentService;
    private final CollegeAdminRepository collegeAdminRepository;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<PagedResponse<CompanyDTO>> getAllCompanies(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        log.info("Fetching all companies");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);

        // SYSTEM_ADMIN can see all, COLLEGE_ADMIN only sees their college's companies
        Long userCollegeId = user.getCollegeId();

        // If collegeId is null, try to get it from CollegeAdmin entity
        if (userCollegeId == null) {
            Optional<CollegeAdmin> collegeAdminOpt = collegeAdminRepository.findByUserId(user.getId());
            if (collegeAdminOpt.isPresent()) {
                userCollegeId = collegeAdminOpt.get().getCollege().getId();
            }
        }

        Page<Company> companies;
        if (userCollegeId == null) {
            companies = companyRepository.findAllWithCollege(PageRequest.of(page, size));
        } else {
            companies = companyRepository.findByCollegeIdWithCollege(userCollegeId, PageRequest.of(page, size));
        }

        List<Long> companyIds = companies.getContent().stream().map(Company::getId).toList();
        Map<Long, List<Long>> batchesByCompany = companyIds.isEmpty()
                ? Map.of()
                : IdGrouping.byOwner(batchRepository.findBatchIdsByCompanyIds(companyIds));

        List<CompanyDTO> items = companies.getContent().stream()
                .map(c -> {
                    CompanyDTO dto = convertToDTO(c);
                    dto.setLinkedBatchIds(IdGrouping.forOwner(batchesByCompany, c.getId()));
                    return dto;
                })
                .toList();

        return ResponseEntity.ok(PagedResponse.<CompanyDTO>builder()
                .items(items)
                .page(companies.getNumber())
                .size(companies.getSize())
                .totalElements(companies.getTotalElements())
                .totalPages(companies.getTotalPages())
                .build());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<CompanyDTO> getCompanyById(@PathVariable Long id) {
        log.info("Fetching company with id: {}", id);
        // Company holds a lazy college. Returning the entity serialises that
        // association outside any transaction, which open-in-view used to paper
        // over; the DTO makes the boundary explicit instead.
        return companyRepository.findByIdWithCollege(id)
                .filter(c -> c.getCollege() != null && TenantGuard.isVisible(c.getCollege().getId()))
                .map(c -> ResponseEntity.ok(convertToDTO(c)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> createCompany(@RequestBody CreateCompanyRequest request) {
        log.info("Creating company: {}", request.name);

        // Get college ID from authenticated user
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        AuthenticatedUser user = SecurityUtils.requirePrincipal(auth);
        Long collegeId = null;

        boolean isSystemAdmin = user.isSystemAdmin();

        // For SYSTEM_ADMIN: use collegeId from request if provided
        // For COLLEGE_ADMIN: use their college ID
        if (isSystemAdmin) {
            // SYSTEM_ADMIN: can specify collegeId in request or use their own
            if (request.collegeId != null) {
                collegeId = request.collegeId;
            } else {
                collegeId = user.getCollegeId();
            }
        } else {
            // COLLEGE_ADMIN: use their college ID
            collegeId = user.getCollegeId();

            // If collegeId is null, try to get it from CollegeAdmin entity
            if (collegeId == null) {
                Optional<CollegeAdmin> collegeAdminOpt = collegeAdminRepository.findByUserId(user.getId());
                if (collegeAdminOpt.isPresent()) {
                    collegeId = collegeAdminOpt.get().getCollege().getId();
                }
            }
        }

        if (collegeId == null) {
            log.error("College ID is null for user: {}", user.getEmail());
            return ResponseEntity.badRequest().body("College ID is required. Please provide a valid college ID.");
        }

        // Verify college exists
        Optional<College> collegeOpt = collegeRepository.findById(collegeId);
        if (collegeOpt.isEmpty()) {
            log.error("College not found with ID: {}", collegeId);
            return ResponseEntity.badRequest().body("College not found with ID: " + collegeId);
        }

        College college = collegeOpt.get();

        Company company = Company.builder()
                .college(college)
                .name(request.name)
                .domain(request.domain)
                .hiringType(request.hiringType)
                .build();

        Company savedCompany = companyRepository.save(company);
        log.info("Company created successfully with ID: {}", savedCompany.getId());
        return ResponseEntity.ok(convertToDTO(savedCompany));
    }

    // Helper method to convert Company entity to DTO
    private CompanyDTO convertToDTO(Company company) {
        return CompanyDTO.builder()
                .id(company.getId())
                .collegeId(company.getCollege() == null ? null : company.getCollege().getId())
                .collegeName(company.getCollege() == null ? null : company.getCollege().getName())
                .name(company.getName())
                .domain(company.getDomain())
                .hiringType(company.getHiringType())
                .createdAt(company.getCreatedAt())
                .updatedAt(company.getUpdatedAt())
                .build();
    }

    // DTO for creating company
    public static class CreateCompanyRequest {
        public String name;
        public String domain;
        public String hiringType; // FULL_TIME, INTERNSHIP, BOTH
        public Long collegeId; // Optional: for SYSTEM_ADMIN to specify which college
        public String hiringProcess; // Not in DB schema yet, ignore for now
        public String notes; // Not in DB schema yet, ignore for now
    }

    /**
     * Edit a company.
     * PUT /api/v1/admin/companies/{id}
     *
     * <p>{@code collegeId} is not editable: moving a company between colleges
     * would silently move every batch link with it, and no screen asks for that.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'COLLEGE_ADMIN')")
    public ResponseEntity<CompanyDTO> updateCompany(
            @PathVariable Long id,
            @RequestBody CreateCompanyRequest request) {
        Company company = companyRepository.findByIdWithCollege(id)
                .filter(c -> c.getCollege() != null && TenantGuard.isVisible(c.getCollege().getId()))
                .orElseThrow(() -> ResourceNotFoundException.of("Company", id));

        if (request.name != null && !request.name.isBlank()) company.setName(request.name);
        if (request.domain != null) company.setDomain(request.domain);
        if (request.hiringType != null && !request.hiringType.isBlank()) {
            company.setHiringType(request.hiringType);
        }
        company.setUpdatedAt(java.time.LocalDateTime.now());
        companyRepository.save(company);

        // Re-read rather than mapping save()'s return value. With no surrounding
        // transaction, saving a detached entity goes through merge(), which hands
        // back a *different* managed instance whose lazy `college` is a fresh
        // uninitialised proxy -- so convertToDTO's getCollege().getName() threw
        // LazyInitializationException even though the instance we loaded had it
        // fetch-joined.
        return ResponseEntity.ok(convertToDTO(companyRepository.findByIdWithCollege(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Company", id))));
    }

    /** Link this company to a batch, from the company's side. */
    @PostMapping("/{id}/batches/{batchId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'COLLEGE_ADMIN')")
    public ResponseEntity<?> linkToBatch(@PathVariable Long id, @PathVariable Long batchId) {
        int count = batchAssignmentService.assignCompany(batchId, id);
        return ResponseEntity.ok(Map.of("success", true, "companyId", id,
                "batchId", batchId, "companyCount", count));
    }

    @DeleteMapping("/{id}/batches/{batchId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'COLLEGE_ADMIN')")
    public ResponseEntity<?> unlinkFromBatch(@PathVariable Long id, @PathVariable Long batchId) {
        int count = batchAssignmentService.unassignCompany(batchId, id);
        return ResponseEntity.ok(Map.of("success", true, "companyId", id,
                "batchId", batchId, "companyCount", count));
    }

    /**
     * Soft-delete this company.
     *
     * <p>Marks {@code deleted_at} rather than removing the row: the audit
     * trail, enrollments and progress history all reference it, and a hard
     * delete would take them with it. Hidden from every read afterwards by the
     * {@code activeFilter}.
     *
     * <p>Refused with 409 COMPANY_LINKED_TO_BATCHES while the company is linked to a live batch.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'COLLEGE_ADMIN')")
    public ResponseEntity<Void> deleteCompany(@PathVariable Long id, Authentication auth) {
        softDeleteService.deleteCompany(id, SecurityUtils.requirePrincipal(auth).getId());
        return ResponseEntity.noContent().build();
    }
}
