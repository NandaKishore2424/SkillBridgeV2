package com.skillbridge.enrollment.dto;

import lombok.*;

import java.time.LocalDateTime;

/**
 * An enrolment request, as the admin queue and the trainer's own list show it.
 *
 * <p>{@code reason} and {@code decisionReason} are two different people
 * talking. The first is why the applicant asked ("joined the cohort late"); the
 * second is why the reviewer said no ("batch is full"), and the entity has kept
 * them apart since the state machine was written.
 *
 * <p>Only the first was ever mapped here, so the reviewer's answer was written
 * to the database and never shown to anybody — including the scheduled job that
 * expires stale applications and records
 * "Batch started before this application was reviewed" as its reason. The
 * applicant saw a rejection with no explanation and their own words quoted back
 * at them.
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
    /** Why the applicant asked. */
    private String reason;
    /** Why the reviewer decided as they did; null while the request is pending. */
    private String decisionReason;
    private Long reviewedBy;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
}
