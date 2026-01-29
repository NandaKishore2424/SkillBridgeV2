package com.skillbridge.timeline.controller;

import com.skillbridge.timeline.dto.CreateSessionRequest;
import com.skillbridge.timeline.dto.TimelineSessionDTO;
import com.skillbridge.timeline.dto.UpdateSessionRequest;
import com.skillbridge.timeline.service.TimelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST Controller for Timeline Management
 * Allows trainers to create and manage timeline sessions for batches
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class TimelineController {

    private final TimelineService timelineService;

    /**
     * Get timeline for a batch
     * GET /api/v1/batches/{batchId}/timeline
     */
    @GetMapping("/batches/{batchId}/timeline")
    @PreAuthorize("hasAnyRole('TRAINER', 'ADMIN')")
    public ResponseEntity<List<TimelineSessionDTO>> getTimeline(@PathVariable Long batchId) {
        log.info("API: Get timeline for batch {}", batchId);
        List<TimelineSessionDTO> timeline = timelineService.getTimelineByBatchId(batchId);
        return ResponseEntity.ok(timeline);
    }

    /**
     * Create a new session for a batch
     * POST /api/v1/batches/{batchId}/timeline/sessions
     */
    @PostMapping("/batches/{batchId}/timeline/sessions")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<TimelineSessionDTO> createSession(
            @PathVariable Long batchId,
            @Valid @RequestBody CreateSessionRequest request) {
        log.info("API: Create session for batch {}", batchId);
        TimelineSessionDTO session = timelineService.createSession(batchId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /**
     * Update a session
     * PUT /api/v1/timeline/sessions/{sessionId}
     */
    @PutMapping("/timeline/sessions/{sessionId}")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<TimelineSessionDTO> updateSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody UpdateSessionRequest request) {
        log.info("API: Update session {}", sessionId);
        TimelineSessionDTO session = timelineService.updateSession(sessionId, request);
        return ResponseEntity.ok(session);
    }

    /**
     * Delete a session
     * DELETE /api/v1/timeline/sessions/{sessionId}
     */
    @DeleteMapping("/timeline/sessions/{sessionId}")
    @PreAuthorize("hasRole('TRAINER')")
    public ResponseEntity<Void> deleteSession(@PathVariable Long sessionId) {
        log.info("API: Delete session {}", sessionId);
        timelineService.deleteSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
