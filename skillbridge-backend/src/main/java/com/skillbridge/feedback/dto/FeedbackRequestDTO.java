package com.skillbridge.feedback.dto;

import com.skillbridge.feedback.entity.FeedbackType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * What a client sends to leave feedback.
 *
 * <p>Exactly one of {@code studentId} / {@code trainerId} is required, and
 * which one is decided by the caller's role, not by the client: a student names
 * the trainer they are reviewing, a trainer names the student. Both are
 * <em>profile</em> ids ({@code students.id} / {@code trainers.id}), which is
 * what the batch and roster endpoints already hand the client.
 */
@Data
public class FeedbackRequestDTO {

    /** Required when a TRAINER is the author; ignored otherwise. */
    private Long studentId;

    /** Required when a STUDENT is the author; ignored otherwise. */
    private Long trainerId;

    @NotNull(message = "Batch ID is required")
    private Long batchId;

    /**
     * Accepted for backwards compatibility and ignored.
     *
     * <p>The direction is derived from the caller's role. Honouring a
     * client-supplied value would let a student file feedback as a trainer.
     *
     * @deprecated the server decides this; the field will be dropped once the
     *             frontend stops sending it.
     */
    @Deprecated(forRemoval = true)
    private FeedbackType type;

    @NotNull(message = "Rating is required")
    @Min(value = 1, message = "Rating must be at least 1")
    @Max(value = 5, message = "Rating must be at most 5")
    private Integer rating;

    @NotBlank(message = "Category is required")
    @Size(max = 100, message = "Category must be at most 100 characters")
    private String category;

    private String comments;
}
