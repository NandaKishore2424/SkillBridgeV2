package com.skillbridge.trainer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainerDashboardStatsDTO {
    private int assignedBatches;
    private int activeBatches;
    private int totalStudents;
    private int pendingProgressUpdates;
}
