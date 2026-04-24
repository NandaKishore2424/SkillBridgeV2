package com.skillbridge.enrollment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request DTO for creating an enrollment request (trainer)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateEnrollmentRequestDTO {
    @NotNull(message = "Batch ID is required")
    private Long batchId;

    @NotNull(message = "Student ID is required")
    private Long studentId;

    @NotBlank(message = "Request type is required (ADD or REMOVE)")
    private String requestType;

    private String reason;
}
