package com.skillbridge.syllabus.dto;

import lombok.*;

import java.time.LocalDateTime;

/**
 * DTO for a syllabus topic
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyllabusTopicDTO {
    private Long id;
    private String name;
    private String description;
    private Integer displayOrder;
    private Boolean isCompleted;
    private LocalDateTime completedAt;
}
