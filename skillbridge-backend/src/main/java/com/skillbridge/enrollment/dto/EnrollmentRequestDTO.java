package com.skillbridge.enrollment.dto;

import lombok.*;

import java.time.LocalDateTime;

/**
 * DTO for enrollment requests from trainers
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrollmentRequestDTO {
    private Long id;
    private Long batchId;
    private String batchName;
    private Long studentId;
    private String studentName;
    private String studentRollNumber;
    private Long trainerId;
    private String trainerName;
    private String requestType; // ADD or REMOVE
    private String status; // PENDING, APPROVED, REJECTED
    private String reason;
    private Long reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
