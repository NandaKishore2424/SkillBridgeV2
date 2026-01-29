package com.skillbridge.trainer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainerStudentDTO {
    private Long id;
    private Long userId;
    private String rollNumber;
    private String fullName;
    private String email;
    private String enrolledAt;
    private ProgressSummary progressSummary;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProgressSummary {
        private int totalTopics;
        private int completedTopics;
        private int inProgressTopics;
        private int pendingTopics;
    }
}
