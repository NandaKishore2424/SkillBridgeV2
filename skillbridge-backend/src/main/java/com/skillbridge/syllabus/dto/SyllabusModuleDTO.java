package com.skillbridge.syllabus.dto;

import lombok.*;

import java.util.List;

/**
 * DTO for a syllabus module with its topics
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
    private List<SyllabusTopicDTO> topics;
    private Integer topicsCount;
    private Integer completedTopicsCount;
}
