package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentDashboardStatsDTO {
    private Integer enrolledBatches;
    private Integer activeBatches;
    private Integer completedBatches;
    private Integer totalTopicsCompleted;
}
