package com.skillbridge.feedback.service;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.feedback.dto.FeedbackRequestDTO;
import com.skillbridge.feedback.dto.FeedbackResponseDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Feedback between the people on a batch.
 *
 * <p>Every method takes the {@link AuthenticatedUser} rather than an email
 * string. The old signature took {@code String userEmail} and every caller
 * passed {@code authentication.getName()} — which, before the principal was
 * fixed, was the {@code User} entity's {@code toString()}, bcrypt hash
 * included. Taking the principal makes it impossible to pass the wrong thing,
 * and saves a lookup by email for a user the filter has already loaded.
 */
public interface FeedbackService {

    FeedbackResponseDTO createFeedback(FeedbackRequestDTO request, AuthenticatedUser caller);

    Page<FeedbackResponseDTO> getFeedbackByBatch(Long batchId, AuthenticatedUser caller, Pageable pageable);

    /**
     * Feedback written <em>about</em> a student, addressed by student profile id
     * — {@code students.id}, which is what the client already holds — not by the
     * {@code users.id} the table actually stores.
     */
    Page<FeedbackResponseDTO> getFeedbackAboutStudent(Long studentId, AuthenticatedUser caller, Pageable pageable);

    /** Everything the caller is party to, given or received. */
    Page<FeedbackResponseDTO> getMyFeedback(AuthenticatedUser caller, Pageable pageable);
}
