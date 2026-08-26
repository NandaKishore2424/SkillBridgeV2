package com.skillbridge.progress.dto;

import com.skillbridge.progress.domain.ProgressStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** One topic's status for one student. */
@Data
@Builder
public class TopicProgressDTO {

    private Long progressId;
    private Long topicId;
    private String topicName;
    private String topicDescription;
    private Integer displayOrder;

    private ProgressStatus status;
    private Integer score;
    private String comment;

    private Long gradedByTrainerId;
    private String gradedByTrainerName;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
