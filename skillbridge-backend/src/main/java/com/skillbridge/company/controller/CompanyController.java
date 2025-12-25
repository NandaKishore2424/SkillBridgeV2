package com.skillbridge.company.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.college.entity.College;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.college.repository.CollegeRepository;
import com.skillbridge.company.entity.Company;
import com.skillbridge.company.repository.CompanyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/admin/companies")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
public class CompanyController {

    private final CompanyRepository companyRepository;
    private final CollegeRepository collegeRepository;
    private final CollegeAdminRepository collegeAdminRepository;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<List<Company>> getAllCompanies() {
        log.info("Fetching all companies");
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        
        // SYSTEM_ADMIN can see all, COLLEGE_ADMIN only sees their college's companies
        Long userCollegeId = user.getCollegeId();
        
        // If collegeId is null, try to get it from CollegeAdmin entity
        if (userCollegeId == null) {
            Optional<CollegeAdmin> collegeAdminOpt = collegeAdminRepository.findByUserId(user.getId());
            if (collegeAdminOpt.isPresent()) {
                userCollegeId = collegeAdminOpt.get().getCollege().getId();
            }
        }
        
        List<Company> companies;
        if (userCollegeId == null) {
            // SYSTEM_ADMIN
            companies = companyRepository.findAll();
        } else {
            // COLLEGE_ADMIN - filter by college
            final Long finalCollegeId = userCollegeId; // Make final for lambda
            companies = companyRepository.findAll().stream()
                .filter(company -> company.getCollege().getId().equals(finalCollegeId))
                .toList();
        }
        return ResponseEntity.ok(companies);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<Company> getCompanyById(@PathVariable Long id) {
        log.info("Fetching company with id: {}", id);
        return companyRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<?> createCompany(@RequestBody CreateCompanyRequest request) {
        log.info("Creating company: {}", request.name);
        
        // Get college ID from authenticated user
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        User user = (User) auth.getPrincipal();
        Long collegeId = null;
        
        // Check if user is SYSTEM_ADMIN
        boolean isSystemAdmin = user.getRoles().stream()
            .anyMatch(role -> role.getName().equals("ROLE_SYSTEM_ADMIN"));
        
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
        return ResponseEntity.ok(savedCompany);
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
}

