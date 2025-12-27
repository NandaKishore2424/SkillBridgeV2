package com.skillbridge.feedback.controller;

import com.skillbridge.feedback.dto.FeedbackRequestDTO;
import com.skillbridge.feedback.dto.FeedbackResponseDTO;
import com.skillbridge.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @PreAuthorize("hasAnyRole('STUDENT', 'TRAINER')")
    public ResponseEntity<FeedbackResponseDTO> createFeedback(@Valid @RequestBody FeedbackRequestDTO request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return ResponseEntity.ok(feedbackService.createFeedback(request, email));
    }

    @GetMapping("/my-feedback")
    @PreAuthorize("hasAnyRole('STUDENT', 'TRAINER')")
    public ResponseEntity<List<FeedbackResponseDTO>> getMyFeedback() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String email = authentication.getName();
        return ResponseEntity.ok(feedbackService.getMyFeedback(email));
    }

    @GetMapping("/batch/{batchId}")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'TRAINER')")
    public ResponseEntity<List<FeedbackResponseDTO>> getFeedbackByBatch(@PathVariable Long batchId) {
        return ResponseEntity.ok(feedbackService.getFeedbackByBatch(batchId));
    }

    @GetMapping("/student/{studentId}")
    @PreAuthorize("hasAnyRole('COLLEGE_ADMIN', 'TRAINER', 'STUDENT')")
    public ResponseEntity<List<FeedbackResponseDTO>> getFeedbackByStudent(@PathVariable Long studentId) {
        // TODO: Add security check to ensure student can only view their own feedback
        return ResponseEntity.ok(feedbackService.getFeedbackByStudent(studentId));
    }
}
