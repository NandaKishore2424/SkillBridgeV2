package com.skillbridge.feedback.dto;

import com.skillbridge.feedback.entity.FeedbackType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class FeedbackResponseDTO {
    private Long id;
    private Long studentId;
    private String studentName;
    private Long trainerId;
    private String trainerName;
    private Long batchId;
    private String batchName;
    private FeedbackType type;
    private Integer rating;
    private String category;
    private String comments;
    private LocalDateTime createdAt;
}
