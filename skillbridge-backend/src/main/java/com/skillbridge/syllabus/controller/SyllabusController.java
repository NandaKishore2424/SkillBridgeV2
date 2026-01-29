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
 * REST Controller for Syllabus Management
 * Allows trainers to create and manage syllabuses for their batches
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class SyllabusController {

    private final SyllabusService syllabusService;

    /**
     * Get complete syllabus for a batch
     * GET /api/v1/batches/{batchId}/syllabus
     */
    @GetMapping("/batches/{batchId}/syllabus")
    @PreAuthorize("hasAnyRole('TRAINER', 'ADMIN')")
    public ResponseEntity<List<SyllabusModuleDTO>> getSyllabus(@PathVariable Long batchId) {
        log.info("API: Get syllabus for batch {}", batchId);
        List<SyllabusModuleDTO> syllabus = syllabusService.getSyllabusByBatchId(batchId);
        return ResponseEntity.ok(syllabus);
    }

    /**
     * Create a new module for a batch
     * POST /api/v1/batches/{batchId}/syllabus/modules
     */
    @PostMapping("/batches/{batchId}/syllabus/modules")
    @PreAuthorize("hasRole('TRAINER')")
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
    @PreAuthorize("hasRole('TRAINER')")
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
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<Void> deleteModule(@PathVariable Long moduleId) {
        log.info("API: Delete module {}", moduleId);
        syllabusService.deleteModule(moduleId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Add a topic to a module
     * POST /api/v1/syllabus/modules/{moduleId}/topics
     */
    @PostMapping("/syllabus/modules/{moduleId}/topics")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<SyllabusTopicDTO> addTopic(
            @PathVariable Long moduleId,
            @Valid @RequestBody CreateTopicRequest request) {
        log.info("API: Add topic to module {}", moduleId);
        SyllabusTopicDTO topic = syllabusService.addTopicToModule(moduleId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(topic);
    }

    /**
     * Update a topic
     * PUT /api/v1/syllabus/topics/{topicId}
     */
    @PutMapping("/syllabus/topics/{topicId}")
    @PreAuthorize("hasRole('TRAINER')")
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
    @PreAuthorize("hasRole('TRAINER')")
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
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<SyllabusTopicDTO> toggleTopicCompletion(@PathVariable Long topicId) {
        log.info("API: Toggle completion for topic {}", topicId);
        SyllabusTopicDTO topic = syllabusService.toggleTopicCompletion(topicId);
        return ResponseEntity.ok(topic);
    }

    /**
     * Copy syllabus from another batch
     * POST /api/v1/batches/{targetBatchId}/syllabus/copy-from/{sourceBatchId}
     */
    @PostMapping("/batches/{targetBatchId}/syllabus/copy-from/{sourceBatchId}")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<List<SyllabusModuleDTO>> copySyllabus(
            @PathVariable Long targetBatchId,
            @PathVariable Long sourceBatchId) {
        log.info("API: Copy syllabus from batch {} to batch {}", sourceBatchId, targetBatchId);
        List<SyllabusModuleDTO> syllabus = syllabusService.copySyllabusFromBatch(sourceBatchId, targetBatchId);
        return ResponseEntity.status(HttpStatus.CREATED).body(syllabus);
    }
}
