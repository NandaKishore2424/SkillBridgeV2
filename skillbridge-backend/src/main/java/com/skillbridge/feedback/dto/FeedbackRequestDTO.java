package com.skillbridge.feedback.dto;

import com.skillbridge.feedback.entity.FeedbackType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class FeedbackRequestDTO {
    private Long studentId; // Optional, depending on who is giving feedback
    private Long trainerId; // Optional
    
    @NotNull(message = "Batch ID is required")
    private Long batchId;

    @NotNull(message = "Feedback type is required")
    private FeedbackType type;

    @NotNull(message = "Rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating must be at most 5")
    private Integer rating;

    @NotBlank(message = "Category is required")
    private String category;

    private String comments;
}
