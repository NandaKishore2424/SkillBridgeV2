package com.skillbridge.college.controller;

import com.skillbridge.college.dto.CollegeDTO;
import com.skillbridge.college.service.CollegeDirectoryService;
import com.skillbridge.common.dto.PagedResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
public class PublicCollegeController {

    private final CollegeDirectoryService collegeDirectory;

    /**
     * Active colleges, for the registration form's picker.
     *
     * <p>This is unauthenticated, which makes it the one list on the platform
     * anybody at all can ask for. It previously called {@code findAll()} and
     * filtered the result in memory, so a single anonymous request read every
     * college row into the heap and then discarded the inactive ones. The
     * status predicate is now in SQL and the result is paged.
     */
    @GetMapping("/active")
    public ResponseEntity<PagedResponse<CollegeDTO>> getActiveColleges(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("Fetching active colleges (public endpoint)");
        return ResponseEntity.ok(collegeDirectory.activeColleges(page, size));
    }
}

