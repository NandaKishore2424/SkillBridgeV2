package com.skillbridge.college.controller;

import com.skillbridge.college.entity.College;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.college.service.CollegeAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<College>> getAllColleges() {
        log.info("Fetching all colleges");
        List<College> colleges = collegeRepository.findAll();
        return ResponseEntity.ok(colleges);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<College> getCollegeById(@PathVariable Long id) {
        log.info("Fetching college with id: {}", id);
        Optional<College> college = collegeRepository.findById(id);
        return college.map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<College> createCollege(@RequestBody College college) {
        log.info("Creating college: {}", college.getName());
        College savedCollege = collegeRepository.save(college);
        return ResponseEntity.ok(savedCollege);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<College> updateCollege(@PathVariable Long id, @RequestBody College college) {
        log.info("Updating college with id: {}", id);
        if (!collegeRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        college.setId(id);
        College updatedCollege = collegeRepository.save(college);
        return ResponseEntity.ok(updatedCollege);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<College> updateCollegeStatus(
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
        return ResponseEntity.ok(updatedCollege);
    }

    @GetMapping("/{collegeId}/admins")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<CollegeAdminResponse>> getCollegeAdmins(@PathVariable Long collegeId) {
        log.info("Fetching admins for college {}", collegeId);
        List<CollegeAdmin> admins = collegeAdminRepository.findByCollegeId(collegeId);
        List<CollegeAdminResponse> responses = admins.stream()
            .map(admin -> {
                CollegeAdminResponse response = new CollegeAdminResponse();
                response.id = admin.getId();
                response.email = admin.getUser().getEmail();
                response.fullName = admin.getFullName();
                response.collegeId = admin.getCollege().getId();
                return response;
            })
            .toList();
        return ResponseEntity.ok(responses);
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

    @GetMapping("/{collegeId}/students")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<?>> getCollegeStudents(@PathVariable Long collegeId) {
        log.info("Fetching students for college {}", collegeId);
        // Return empty list for now - will be implemented later
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/{collegeId}/batches")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<?>> getCollegeBatches(@PathVariable Long collegeId) {
        log.info("Fetching batches for college {}", collegeId);
        // Return empty list for now - will be implemented later
        return ResponseEntity.ok(List.of());
    }

    @GetMapping("/{collegeId}/trainers")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<?>> getCollegeTrainers(@PathVariable Long collegeId) {
        log.info("Fetching trainers for college {}", collegeId);
        // Return empty list for now - will be implemented later
        return ResponseEntity.ok(List.of());
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

