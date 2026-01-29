package com.skillbridge.syllabus.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

/**
 * Request DTO for creating a topic within a module
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateTopicRequest {
    @NotBlank(message = "Topic name is required")
    private String name;

    private String description;

    @NotNull(message = "Display order is required")
    private Integer displayOrder;
}
