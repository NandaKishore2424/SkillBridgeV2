package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Counters on the student's landing page.
 *
 * <p>Every field here used to be a hardcoded zero. They are now computed by a
 * single aggregate query — see
 * {@code EnrollmentRepository.aggregateStatsForStudent}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentDashboardStatsDTO {

    private Integer enrolledBatches;
    private Integer activeBatches;
    private Integer completedBatches;
    private Integer upcomingBatches;

    /** Applications submitted and not yet reviewed. */
    private Integer pendingApplications;

    private Integer totalTopicsCompleted;
    private Integer totalTopicsAssigned;

    /**
     * Weighted completion across every enrolled batch, 0–100.
     *
     * <p>Weighted rather than completed/assigned, so partially finished work
     * counts for something. See {@code ProgressStatus.weight()}.
     */
    private Integer overallProgressPercent;
}
