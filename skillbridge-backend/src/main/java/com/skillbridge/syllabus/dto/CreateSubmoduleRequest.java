package com.skillbridge.syllabus.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Request DTO for creating a sub-module with topics
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateSubmoduleRequest {
    @NotBlank(message = "Sub-module name is required")
    private String name;

    private String description;

    @NotNull(message = "Display order is required")
    private Integer displayOrder;

    // Scheduling fields
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer weekNumber;

    @Valid
    private List<CreateTopicRequest> topics;
}
