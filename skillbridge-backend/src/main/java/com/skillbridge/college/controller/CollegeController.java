package com.skillbridge.college.controller;

import com.skillbridge.batch.dto.BatchDTO;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.college.dto.CollegeDTO;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.college.service.CollegeAdminService;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.common.exception.ResourceNotFoundException;
import com.skillbridge.student.dto.StudentDTO;
import com.skillbridge.student.service.StudentService;
import com.skillbridge.trainer.dto.TrainerDTO;
import com.skillbridge.trainer.service.TrainerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/admin/colleges")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
public class CollegeController {

    private final CollegeRepository collegeRepository;
    private final CollegeAdminService collegeAdminService;
    private final CollegeAdminRepository collegeAdminRepository;
    private final StudentService studentService;
    private final TrainerService trainerService;
    private final BatchRepository batchRepository;

    /**
     * Every college on the platform.
     *
     * <p>Paged: this is the one list that grows with the product rather than
     * with any single tenant, and it is the SYSTEM_ADMIN's landing screen.
     */
    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<PagedResponse<CollegeDTO>> getAllColleges(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Fetching all colleges");
        return ResponseEntity.ok(PagedResponse.from(
                collegeRepository.findAll(Pagination.of(page, size)), CollegeDTO::from));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<CollegeDTO> getCollegeById(@PathVariable Long id) {
        log.info("Fetching college with id: {}", id);
        Optional<College> college = collegeRepository.findById(id);
        return college.map(CollegeDTO::from).map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<CollegeDTO> createCollege(@RequestBody College college) {
        log.info("Creating college: {}", college.getName());
        College savedCollege = collegeRepository.save(college);
        return ResponseEntity.ok(CollegeDTO.from(savedCollege));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<CollegeDTO> updateCollege(@PathVariable Long id, @RequestBody College college) {
        log.info("Updating college with id: {}", id);
        if (!collegeRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        college.setId(id);
        College updatedCollege = collegeRepository.save(college);
        return ResponseEntity.ok(CollegeDTO.from(updatedCollege));
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<CollegeDTO> updateCollegeStatus(
            @PathVariable Long id,
            @RequestBody StatusUpdateRequest request
    ) {
        log.info("Updating college status for id: {} to {}", id, request.status);
        Optional<College> collegeOpt = collegeRepository.findById(id);
        if (collegeOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        College college = collegeOpt.get();
        college.setStatus(request.status);
        College updatedCollege = collegeRepository.save(college);
        return ResponseEntity.ok(CollegeDTO.from(updatedCollege));
    }

    @GetMapping("/{collegeId}/admins")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<PagedResponse<CollegeAdminResponse>> getCollegeAdmins(
            @PathVariable Long collegeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Fetching admins for college {}", collegeId);
        requireCollege(collegeId);
        return ResponseEntity.ok(PagedResponse.from(
                collegeAdminRepository.findByCollege(collegeId, Pagination.of(page, size)),
                CollegeController::toAdminResponse));
    }

    @PostMapping("/{collegeId}/admins")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<CollegeAdminResponse> createCollegeAdmin(
            @PathVariable Long collegeId,
            @RequestBody CreateCollegeAdminRequest request
    ) {
        log.info("Creating college admin for college {} with email: {}", collegeId, request.email);
        try {
            CollegeAdminService.CreateCollegeAdminRequest serviceRequest = 
                new CollegeAdminService.CreateCollegeAdminRequest();
            serviceRequest.email = request.email;
            serviceRequest.password = request.password;
            serviceRequest.fullName = request.fullName;
            serviceRequest.phone = request.phone;

            CollegeAdmin admin = collegeAdminService.createCollegeAdmin(collegeId, serviceRequest);
            
            CollegeAdminResponse response = new CollegeAdminResponse();
            response.id = admin.getId();
            response.email = admin.getUser().getEmail();
            response.fullName = admin.getFullName();
            response.collegeId = admin.getCollege().getId();
            
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            log.error("Failed to create college admin: {}", e.getMessage());
            throw e;
        }
    }

    /**
     * One college's students, batches and trainers, for the SYSTEM_ADMIN's
     * college detail screen.
     *
     * <p>All three returned a hard-coded {@code List.of()} with a "will be
     * implemented later" comment, so the three tabs on that screen have always
     * rendered empty. They are the college-scoped reads the college admin's own
     * endpoints already do; the only difference is that the college comes from
     * the path rather than from the caller's token, which is exactly what a
     * SYSTEM_ADMIN needs and why they could not simply reuse those routes.
     *
     * <p>An unknown college is a 404 rather than an empty page: "this college
     * has no students" and "there is no such college" are different answers.
     */
    @GetMapping("/{collegeId}/students")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<PagedResponse<StudentDTO>> getCollegeStudents(
            @PathVariable Long collegeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Fetching students for college {}", collegeId);
        requireCollege(collegeId);
        return ResponseEntity.ok(PagedResponse.from(
                studentService.getStudentsByCollege(collegeId, null, null, Pagination.of(page, size))));
    }

    @GetMapping("/{collegeId}/batches")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<PagedResponse<BatchDTO>> getCollegeBatches(
            @PathVariable Long collegeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Fetching batches for college {}", collegeId);
        requireCollege(collegeId);
        var batches = batchRepository.findByCollegeIdWithCollege(collegeId, Pagination.of(page, size));
        List<Long> ids = batches.getContent().stream().map(b -> b.getId()).toList();
        Map<Long, Long> trainers = countsById(batchRepository.countTrainersByBatchIds(ids));
        Map<Long, Long> companies = countsById(batchRepository.countCompaniesByBatchIds(ids));
        Map<Long, Long> students = countsById(batchRepository.countEnrollmentsByBatchIds(ids));
        return ResponseEntity.ok(PagedResponse.from(batches, b -> BatchDTO.from(b,
                trainers.getOrDefault(b.getId(), 0L),
                companies.getOrDefault(b.getId(), 0L),
                students.getOrDefault(b.getId(), 0L))));
    }

    @GetMapping("/{collegeId}/trainers")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<PagedResponse<TrainerDTO>> getCollegeTrainers(
            @PathVariable Long collegeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Fetching trainers for college {}", collegeId);
        requireCollege(collegeId);
        return ResponseEntity.ok(PagedResponse.from(
                trainerService.getTrainersByCollege(collegeId, null, null, Pagination.of(page, size))));
    }

    /** One grouped count query per page, keyed by batch id. */
    private static Map<Long, Long> countsById(List<Object[]> rows) {
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : rows) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** 404 for a college that does not exist, before any sub-resource is read. */
    private void requireCollege(Long collegeId) {
        if (!collegeRepository.existsById(collegeId)) {
            throw ResourceNotFoundException.of("College", collegeId);
        }
    }

    private static CollegeAdminResponse toAdminResponse(CollegeAdmin admin) {
        CollegeAdminResponse response = new CollegeAdminResponse();
        response.id = admin.getId();
        response.email = admin.getUser().getEmail();
        response.fullName = admin.getFullName();
        response.collegeId = admin.getCollege().getId();
        return response;
    }

    // DTO for status update
    public static class StatusUpdateRequest {
        public String status;
    }

    // DTO for creating college admin
    public static class CreateCollegeAdminRequest {
        public String email;
        public String password;
        public String fullName;
        public String phone;
    }

    // DTO for college admin response
    public static class CollegeAdminResponse {
        public Long id;
        public String email;
        public String fullName;
        public Long collegeId;
    }
}

