package com.skillbridge.college.controller;

import com.skillbridge.college.entity.College;
import com.skillbridge.college.repository.CollegeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Public College Controller
 * 
 * Public endpoints for college information (no authentication required)
 * Used for registration forms, landing pages, etc.
 */
@RestController
@RequestMapping("/api/v1/colleges")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"http://localhost:5173", "http://localhost:3000"})
public class PublicCollegeController {

    private final CollegeRepository collegeRepository;

    /**
     * Get all active colleges (public endpoint for registration)
     */
    @GetMapping("/active")
    public ResponseEntity<List<College>> getActiveColleges() {
        log.info("Fetching active colleges (public endpoint)");
        List<College> activeColleges = collegeRepository.findAll().stream()
            .filter(college -> "ACTIVE".equals(college.getStatus()))
            .collect(Collectors.toList());
        return ResponseEntity.ok(activeColleges);
    }
}

