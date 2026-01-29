package com.skillbridge.syllabus.dto;

import lombok.*;

/**
 * Request DTO for updating a topic
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateTopicRequest {
    private String name;
    private String description;
    private Integer displayOrder;
}
