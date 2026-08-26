package com.skillbridge.student.dto;

import com.skillbridge.enrollment.domain.EnrollmentStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * The result of a student applying to a batch.
 *
 * <p>Replaces the {@code Map<String, Object>} the endpoint used to return, which
 * carried a hardcoded {@code id: 1} and the message "Application functionality
 * coming soon" while persisting nothing.
 */
@Data
@Builder
public class BatchApplicationDTO {

    private Long applicationId;
    private Long batchId;
    private String batchName;
    private EnrollmentStatus status;
    private LocalDateTime appliedAt;

    /**
     * True when this response describes an application that already existed.
     *
     * <p>A retried request returns the original application with this set, rather
     * than creating a second one or failing — the caller gets the same answer
     * either way, which is the point of an idempotent write.
     */
    private boolean duplicate;

    private String message;
}
