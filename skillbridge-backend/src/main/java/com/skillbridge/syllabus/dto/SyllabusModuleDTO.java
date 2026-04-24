package com.skillbridge.syllabus.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * DTO for a curriculum module with its sub-modules
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyllabusModuleDTO {
    private Long id;
    private String name;
    private String description;
    private Integer displayOrder;

    // Scheduling
    private LocalDate startDate;
    private LocalDate endDate;

    // Sub-modules (which contain topics)
    private List<SyllabusSubmoduleDTO> submodules;
    private Integer submodulesCount;
    private Integer totalTopicsCount;
    private Integer completedTopicsCount;
}
