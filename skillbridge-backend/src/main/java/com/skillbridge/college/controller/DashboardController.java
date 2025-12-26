package com.skillbridge.college.controller;

import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.repository.BatchRepository;
import com.skillbridge.college.entity.CollegeAdmin;
import com.skillbridge.college.repository.CollegeAdminRepository;
import com.skillbridge.company.repository.CompanyRepository;
import com.skillbridge.student.repository.StudentRepository;
import com.skillbridge.trainer.repository.TrainerRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = { "http://localhost:5173", "http://localhost:3000" })
public class DashboardController {

    private final BatchRepository batchRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final CompanyRepository companyRepository;
    private final CollegeAdminRepository collegeAdminRepository;

    @GetMapping("/stats")
    @PreAuthorize("hasRole('COLLEGE_ADMIN')")
    public ResponseEntity<DashboardStats> getDashboardStats() {
        log.info("Fetching dashboard stats for college admin");

        try {
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
                log.error("College ID is null for user: {}", user.getEmail());
                return ResponseEntity.badRequest().build();
            }

            // Get counts
            long totalBatches = batchRepository.countByCollegeId(collegeId);
            long activeBatches = batchRepository.countByCollegeIdAndStatus(collegeId, "ACTIVE");
            long totalStudents = studentRepository.countByCollegeId(collegeId);
            long totalTrainers = trainerRepository.countByCollegeId(collegeId);
            long totalCompanies = companyRepository.countByCollegeId(collegeId);

            DashboardStats stats = DashboardStats.builder()
                    .totalBatches(totalBatches)
                    .activeBatches(activeBatches)
                    .totalStudents(totalStudents)
                    .totalTrainers(totalTrainers)
                    .totalCompanies(totalCompanies)
                    .build();

            log.info("Dashboard stats: {}", stats);
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            log.error("Error fetching dashboard stats", e);
            throw new RuntimeException("Failed to fetch dashboard stats: " + e.getMessage(), e);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DashboardStats {
        private long totalBatches;
        private long activeBatches;
        private long totalStudents;
        private long totalTrainers;
        private long totalCompanies;
    }
}
