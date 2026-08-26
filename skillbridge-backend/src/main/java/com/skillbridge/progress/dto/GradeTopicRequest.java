package com.skillbridge.progress.dto;

import com.skillbridge.progress.domain.ProgressStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Grade one student on one topic. */
@Data
public class GradeTopicRequest {

    @NotNull(message = "Student id is required")
    private Long studentId;

    @NotNull(message = "Status is required")
    private ProgressStatus status;

    @Min(value = 0, message = "Score cannot be negative")
    @Max(value = 100, message = "Score cannot exceed 100")
    private Integer score;

    @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
    private String comment;
}
