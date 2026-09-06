package com.skillbridge.feedback.controller;

import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.feedback.dto.FeedbackRequestDTO;
import com.skillbridge.feedback.dto.FeedbackResponseDTO;
import com.skillbridge.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Feedback endpoints.
 *
 * <p>Paths and payloads are unchanged from what {@code src/api/feedback.ts}
 * already calls. What changed underneath is that they now work: the entity
 * points at a table that exists, and the principal is resolved through
 * {@link SecurityUtils} rather than {@code authentication.getName()} on a raw
 * JPA entity.
 */
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @PreAuthorize("hasAnyRole('STUDENT', 'TRAINER')")
    public ResponseEntity<FeedbackResponseDTO> createFeedback(
            @Valid @RequestBody FeedbackRequestDTO request, Authentication authentication) {
        FeedbackResponseDTO created =
                feedbackService.createFeedback(request, SecurityUtils.requirePrincipal(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Everything the caller is party to, in either direction. */
    @GetMapping("/my-feedback")
    @PreAuthorize("hasAnyRole('STUDENT', 'TRAINER')")
    public ResponseEntity<List<FeedbackResponseDTO>> getMyFeedback(Authentication authentication) {
        return ResponseEntity.ok(feedbackService.getMyFeedback(SecurityUtils.requirePrincipal(authentication)));
    }

    @GetMapping("/batch/{batchId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'COLLEGE_ADMIN', 'TRAINER')")
    public ResponseEntity<List<FeedbackResponseDTO>> getFeedbackByBatch(
            @PathVariable Long batchId, Authentication authentication) {
        return ResponseEntity.ok(
                feedbackService.getFeedbackByBatch(batchId, SecurityUtils.requirePrincipal(authentication)));
    }

    /**
     * Feedback about one student, by student profile id.
     *
     * <p>Deliberately not open to STUDENT. The previous version allowed it with
     * a {@code TODO} about adding an ownership check, which meant any student
     * could read any other student's feedback by incrementing the id. A student
     * wanting their own feedback already has {@code /my-feedback}.
     */
    @GetMapping("/student/{studentId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'COLLEGE_ADMIN', 'TRAINER')")
    public ResponseEntity<List<FeedbackResponseDTO>> getFeedbackByStudent(
            @PathVariable Long studentId, Authentication authentication) {
        return ResponseEntity.ok(
                feedbackService.getFeedbackAboutStudent(studentId, SecurityUtils.requirePrincipal(authentication)));
    }
}
