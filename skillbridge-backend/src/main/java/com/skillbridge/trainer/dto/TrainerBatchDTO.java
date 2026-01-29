package com.skillbridge.trainer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainerBatchDTO {
    private Long id;
    private String name;
    private String description;
    private String status;
    private String startDate;
    private String endDate;
    private int enrolledCount;
    private SyllabusInfo syllabus;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyllabusInfo {
        private Long id;
        private String title;
        private int topicCount;
    }
}
