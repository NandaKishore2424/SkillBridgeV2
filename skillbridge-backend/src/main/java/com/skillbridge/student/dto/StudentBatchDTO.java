package com.skillbridge.student.dto;

import com.skillbridge.batch.dto.BatchDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class StudentBatchDTO extends BatchDTO {
    private LocalDateTime enrolledAt;
    private List<TrainerInfo> trainers;
    private List<CompanyInfo> companies;
    private ProgressInfo progress;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TrainerInfo {
        private Long id;
        private String fullName;
        private String email;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompanyInfo {
        private Long id;
        private String name;
        private String domain;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressInfo {
        private Integer totalTopics;
        private Integer completedTopics;
        private Integer inProgressTopics;
        private Integer pendingTopics;
        private Double completionPercentage;
    }
}
