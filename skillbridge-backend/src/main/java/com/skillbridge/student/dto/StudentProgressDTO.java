package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentProgressDTO {
    private Long batchId;
    private String batchName;
    private List<TopicProgress> topics;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TopicProgress {
        private Long id;
        private String title;
        private String description;
        private String status; // PENDING, IN_PROGRESS, COMPLETED, NEEDS_IMPROVEMENT
        private String feedback;
        private LocalDateTime updatedAt;
    }
}
