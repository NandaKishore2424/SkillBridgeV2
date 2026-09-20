package com.skillbridge.college.controller;

import com.skillbridge.common.exception.ForbiddenException;
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
import com.skillbridge.common.security.CollegeAdminOnly;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import com.skillbridge.common.exception.InternalServerException;
import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final BatchRepository batchRepository;
    private final StudentRepository studentRepository;
    private final TrainerRepository trainerRepository;
    private final CompanyRepository companyRepository;
    private final CollegeAdminRepository collegeAdminRepository;

    @GetMapping("/stats")
    @CollegeAdminOnly
    public ResponseEntity<DashboardStats> getDashboardStats() {
        log.info("Fetching dashboard stats for college admin");

        // 403 for an account with no college (SecurityUtils). This used to sit in a
        // catch-all that turned every failure, that one included, into a 500.
        Long collegeId = SecurityUtils.requireCollegeId();

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
