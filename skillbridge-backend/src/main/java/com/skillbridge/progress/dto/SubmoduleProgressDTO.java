package com.skillbridge.progress.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** A sub-module and the student's status on each of its topics. */
@Data
@Builder
public class SubmoduleProgressDTO {

    private Long submoduleId;
    private String submoduleName;
    private Integer displayOrder;
    private Integer weekNumber;

    private int topicsTotal;
    private int topicsCompleted;
    private double weightedPercent;

    private List<TopicProgressDTO> topics;
}
