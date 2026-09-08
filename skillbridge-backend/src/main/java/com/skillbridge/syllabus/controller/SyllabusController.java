package com.skillbridge.syllabus.controller;

import com.skillbridge.syllabus.dto.*;
import com.skillbridge.syllabus.service.SyllabusService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Curriculum Management
 * Allows trainers to create and manage curriculum (modules, sub-modules,
 * topics) for their batches
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class SyllabusController {

    private final SyllabusService syllabusService;

    // ===================================================================
    // CURRICULUM - Full Structure
    // ===================================================================

    /**
     * Get complete curriculum for a batch
     * GET /api/v1/batches/{batchId}/syllabus
     *
     * <p><b>Deliberately not paginated</b>, and the only list-shaped read in the
     * API that is not. Every other one was converted in Phase 02 Task 3; this
     * one was considered and left, so that a future reader finds a decision
     * here rather than an oversight.
     *
     * <p>Three reasons. It is a tree, not a list: the client renders modules,
     * submodules and topics together and reorders modules by
     * {@code displayOrder}, which cannot work if module 3 and module 1 are on
     * different pages. Paginating the modules would bound only the outer
     * dimension — a single module's submodules and topics would still be
     * unbounded — so it buys less than the truncation it risks. And the query
     * already {@code LEFT JOIN FETCH}es the submodule collection, so adding a
     * {@code Pageable} to it would page in memory rather than in SQL: an
     * HHH000104 warning and no actual bound, which is worse than not paginating
     * because it reads as done.
     *
     * <p>The residual risk is real and accepted: a curriculum with thousands of
     * modules would return all of them. The bound today is that a human authors
     * the curriculum. If that ever stops being true — bulk import, or copying
     * curricula in a loop — the fix is a documented cap on the tree size rather
     * than offset pagination.
     */
    @GetMapping("/batches/{batchId}/syllabus")
    // Was hasAnyRole('TRAINER', 'ADMIN'). There is no ADMIN role in this system
    // -- the four are SYSTEM_ADMIN, COLLEGE_ADMIN, TRAINER and STUDENT -- so the
    // second clause matched nobody and a college admin got 403 reading the
    // curriculum of a batch they had created. Same defect Session 2 fixed in
    // AdminEnrollmentController.
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<List<SyllabusModuleDTO>> getSyllabus(@PathVariable Long batchId) {
        log.info("API: Get syllabus for batch {}", batchId);
        List<SyllabusModuleDTO> syllabus = syllabusService.getCurriculumByBatchId(batchId);
        return ResponseEntity.ok(syllabus);
    }

    /**
     * Copy an entire curriculum from another batch.
     * POST /api/v1/batches/{batchId}/syllabus/copy-from/{sourceBatchId}
     *
     * <p>The "Copy from Batch" button on the syllabus screen has called this
     * since the screen was built; the endpoint did not exist.
     */
    @PostMapping("/batches/{batchId}/syllabus/copy-from/{sourceBatchId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<List<SyllabusModuleDTO>> copySyllabus(
            @PathVariable Long batchId,
            @PathVariable Long sourceBatchId) {
        log.info("API: Copy curriculum from batch {} to batch {}", sourceBatchId, batchId);
        return ResponseEntity.ok(syllabusService.copyCurriculum(batchId, sourceBatchId));
    }

    // ===================================================================
    // MODULE Endpoints
    // ===================================================================

    /**
     * Create a new module for a batch
     * POST /api/v1/batches/{batchId}/syllabus/modules
     */
    @PostMapping("/batches/{batchId}/syllabus/modules")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<SyllabusModuleDTO> createModule(
            @PathVariable Long batchId,
            @Valid @RequestBody CreateModuleRequest request) {
        log.info("API: Create module for batch {}", batchId);
        SyllabusModuleDTO module = syllabusService.createModule(batchId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(module);
    }

    /**
     * Update a module
     * PUT /api/v1/syllabus/modules/{moduleId}
     */
    @PutMapping("/syllabus/modules/{moduleId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<SyllabusModuleDTO> updateModule(
            @PathVariable Long moduleId,
            @Valid @RequestBody UpdateModuleRequest request) {
        log.info("API: Update module {}", moduleId);
        SyllabusModuleDTO module = syllabusService.updateModule(moduleId, request);
        return ResponseEntity.ok(module);
    }

    /**
     * Delete a module
     * DELETE /api/v1/syllabus/modules/{moduleId}
     */
    @DeleteMapping("/syllabus/modules/{moduleId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<Void> deleteModule(@PathVariable Long moduleId) {
        log.info("API: Delete module {}", moduleId);
        syllabusService.deleteModule(moduleId);
        return ResponseEntity.noContent().build();
    }

    // ===================================================================
    // SUB-MODULE Endpoints
    // ===================================================================

    /**
     * Create a sub-module under a module
     * POST /api/v1/syllabus/modules/{moduleId}/submodules
     */
    @PostMapping("/syllabus/modules/{moduleId}/submodules")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<SyllabusSubmoduleDTO> createSubmodule(
            @PathVariable Long moduleId,
            @Valid @RequestBody CreateSubmoduleRequest request) {
        log.info("API: Create sub-module for module {}", moduleId);
        SyllabusSubmoduleDTO submodule = syllabusService.createSubmodule(moduleId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(submodule);
    }

    /**
     * Update a sub-module
     * PUT /api/v1/syllabus/submodules/{submoduleId}
     */
    @PutMapping("/syllabus/submodules/{submoduleId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<SyllabusSubmoduleDTO> updateSubmodule(
            @PathVariable Long submoduleId,
            @Valid @RequestBody UpdateSubmoduleRequest request) {
        log.info("API: Update sub-module {}", submoduleId);
        SyllabusSubmoduleDTO submodule = syllabusService.updateSubmodule(submoduleId, request);
        return ResponseEntity.ok(submodule);
    }

    /**
     * Delete a sub-module
     * DELETE /api/v1/syllabus/submodules/{submoduleId}
     */
    @DeleteMapping("/syllabus/submodules/{submoduleId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<Void> deleteSubmodule(@PathVariable Long submoduleId) {
        log.info("API: Delete sub-module {}", submoduleId);
        syllabusService.deleteSubmodule(submoduleId);
        return ResponseEntity.noContent().build();
    }

    // ===================================================================
    // TOPIC Endpoints
    // ===================================================================

    /**
     * Add a topic to a sub-module
     * POST /api/v1/syllabus/submodules/{submoduleId}/topics
     */
    @PostMapping("/syllabus/submodules/{submoduleId}/topics")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<SyllabusTopicDTO> addTopic(
            @PathVariable Long submoduleId,
            @Valid @RequestBody CreateTopicRequest request) {
        log.info("API: Add topic to sub-module {}", submoduleId);
        SyllabusTopicDTO topic = syllabusService.addTopicToSubmodule(submoduleId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(topic);
    }

    /**
     * Update a topic
     * PUT /api/v1/syllabus/topics/{topicId}
     */
    @PutMapping("/syllabus/topics/{topicId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<SyllabusTopicDTO> updateTopic(
            @PathVariable Long topicId,
            @Valid @RequestBody UpdateTopicRequest request) {
        log.info("API: Update topic {}", topicId);
        SyllabusTopicDTO topic = syllabusService.updateTopic(topicId, request);
        return ResponseEntity.ok(topic);
    }

    /**
     * Delete a topic
     * DELETE /api/v1/syllabus/topics/{topicId}
     */
    @DeleteMapping("/syllabus/topics/{topicId}")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<Void> deleteTopic(@PathVariable Long topicId) {
        log.info("API: Delete topic {}", topicId);
        syllabusService.deleteTopic(topicId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Toggle topic completion
     * POST /api/v1/syllabus/topics/{topicId}/toggle-completion
     */
    @PostMapping("/syllabus/topics/{topicId}/toggle-completion")
    @PreAuthorize("hasAnyRole('TRAINER', 'COLLEGE_ADMIN')")
    public ResponseEntity<SyllabusTopicDTO> toggleTopicCompletion(@PathVariable Long topicId) {
        log.info("API: Toggle completion for topic {}", topicId);
        SyllabusTopicDTO topic = syllabusService.toggleTopicCompletion(topicId);
        return ResponseEntity.ok(topic);
    }
}
