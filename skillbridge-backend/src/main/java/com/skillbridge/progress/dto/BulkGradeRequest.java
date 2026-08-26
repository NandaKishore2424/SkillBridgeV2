package com.skillbridge.progress.dto;

import com.skillbridge.progress.domain.ProgressStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * Grade many students on one topic in a single call.
 *
 * <p>This is the shape the work actually takes: a trainer finishes a topic and
 * marks the whole class. Sending one request per student turns a class of forty
 * into forty round trips, forty transactions and forty chances to half-fail.
 */
@Data
public class BulkGradeRequest {

    @NotEmpty(message = "At least one student id is required")
    @Size(max = 500, message = "Cannot grade more than 500 students in one request")
    private List<Long> studentIds;

    @NotNull(message = "Status is required")
    private ProgressStatus status;

    @Min(value = 0, message = "Score cannot be negative")
    @Max(value = 100, message = "Score cannot exceed 100")
    private Integer score;

    @Size(max = 2000, message = "Comment cannot exceed 2000 characters")
    private String comment;
}
