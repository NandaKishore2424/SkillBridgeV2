package com.skillbridge.feedback.service;

import com.skillbridge.feedback.dto.FeedbackRequestDTO;
import com.skillbridge.feedback.dto.FeedbackResponseDTO;
import com.skillbridge.feedback.entity.FeedbackType;

import java.util.List;

public interface FeedbackService {
    FeedbackResponseDTO createFeedback(FeedbackRequestDTO request, String userEmail);
    List<FeedbackResponseDTO> getFeedbackByBatch(Long batchId);
    List<FeedbackResponseDTO> getFeedbackByStudent(Long studentId);
    List<FeedbackResponseDTO> getFeedbackByTrainer(Long trainerId);
    List<FeedbackResponseDTO> getMyFeedback(String email); // For logged in user
}
