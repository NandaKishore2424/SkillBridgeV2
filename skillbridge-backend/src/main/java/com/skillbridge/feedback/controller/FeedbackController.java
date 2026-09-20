package com.skillbridge.feedback.controller;

import com.skillbridge.auth.security.SecurityUtils;
import com.skillbridge.common.dto.PagedResponse;
import com.skillbridge.common.dto.Pagination;
import com.skillbridge.feedback.dto.FeedbackRequestDTO;
import com.skillbridge.feedback.dto.FeedbackResponseDTO;
import com.skillbridge.feedback.service.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.skillbridge.common.security.StaffOnly;
import com.skillbridge.common.security.StudentOrTrainer;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Feedback endpoints.
 *
 * <p>The three reads return a {@link PagedResponse}. Feedback is append-only
 * and nothing prunes it, so every one of these lists grows for the life of the
 * account, the batch or the student it is keyed by — {@code /my-feedback} for a
 * trainer accumulates a row per student per batch per term, indefinitely.
 *
 * <p>The principal is resolved through {@link SecurityUtils} rather than
 * {@code authentication.getName()} on a raw JPA entity.
 */
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    @StudentOrTrainer
    public ResponseEntity<FeedbackResponseDTO> createFeedback(
            @Valid @RequestBody FeedbackRequestDTO request, Authentication authentication) {
        FeedbackResponseDTO created =
                feedbackService.createFeedback(request, SecurityUtils.requirePrincipal(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Everything the caller is party to, in either direction. */
    @GetMapping("/my-feedback")
    @StudentOrTrainer
    public ResponseEntity<PagedResponse<FeedbackResponseDTO>> getMyFeedback(
            Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(feedbackService.getMyFeedback(
                SecurityUtils.requirePrincipal(authentication), Pagination.of(page, size))));
    }

    @GetMapping("/batch/{batchId}")
    @StaffOnly
    public ResponseEntity<PagedResponse<FeedbackResponseDTO>> getFeedbackByBatch(
            @PathVariable Long batchId, Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(feedbackService.getFeedbackByBatch(
                batchId, SecurityUtils.requirePrincipal(authentication), Pagination.of(page, size))));
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
    @StaffOnly
    public ResponseEntity<PagedResponse<FeedbackResponseDTO>> getFeedbackByStudent(
            @PathVariable Long studentId, Authentication authentication,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(PagedResponse.from(feedbackService.getFeedbackAboutStudent(
                studentId, SecurityUtils.requirePrincipal(authentication), Pagination.of(page, size))));
    }
}
