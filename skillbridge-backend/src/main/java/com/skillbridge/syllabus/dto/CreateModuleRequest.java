package com.skillbridge.syllabus.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

/**
 * Request DTO for creating a module with topics
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateModuleRequest {
    @NotBlank(message = "Module name is required")
    private String name;

    private String description;

    @NotNull(message = "Display order is required")
    private Integer displayOrder;

    @Valid
    private List<CreateTopicRequest> topics;
}
