package com.skillbridge.syllabus.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * DTO for a syllabus sub-module with its topics
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyllabusSubmoduleDTO {
    private Long id;
    private String name;
    private String description;
    private Integer displayOrder;

    // Scheduling
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer weekNumber;

    // Topics
    private List<SyllabusTopicDTO> topics;
    private Integer topicsCount;
    private Integer completedTopicsCount;
}
