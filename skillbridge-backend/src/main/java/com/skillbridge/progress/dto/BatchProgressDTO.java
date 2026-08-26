package com.skillbridge.progress.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A student's complete progress through one batch, shaped as the curriculum tree
 * so the UI can render it directly without regrouping anything client-side.
 */
@Data
@Builder
public class BatchProgressDTO {

    private Long batchId;
    private String batchName;
    private Long studentId;
    private String studentName;

    private int topicsTotal;
    private int topicsCompleted;
    private int topicsInProgress;
    private int topicsNeedsWork;
    private int topicsPending;

    /** Weighted, not completed/total. Partially-done work counts for something. */
    private double weightedPercent;

    private Double averageScore;
    private LocalDateTime lastActivityAt;

    private List<ModuleProgressDTO> modules;
}
