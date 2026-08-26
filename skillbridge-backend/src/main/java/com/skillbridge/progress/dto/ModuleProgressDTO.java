package com.skillbridge.progress.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** A curriculum module and the student's progress through it. */
@Data
@Builder
public class ModuleProgressDTO {

    private Long moduleId;
    private String moduleName;
    private Integer displayOrder;
    private LocalDate startDate;
    private LocalDate endDate;

    private int topicsTotal;
    private int topicsCompleted;
    private double weightedPercent;

    private List<SubmoduleProgressDTO> submodules;
}
