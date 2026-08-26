package com.skillbridge.progress.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** One row of a trainer's batch overview or at-risk report. */
@Data
@Builder
public class StudentProgressSummaryDTO {

    private Long studentId;
    private String studentName;
    private String rollNumber;

    private int topicsTotal;
    private int topicsCompleted;
    private int topicsNeedsWork;
    private double weightedPercent;
    private Double averageScore;

    private LocalDateTime lastActivityAt;

    /** True when this student is below the batch's at-risk threshold. */
    private boolean atRisk;
}
