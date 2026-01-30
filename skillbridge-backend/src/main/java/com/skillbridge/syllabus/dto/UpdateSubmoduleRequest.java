package com.skillbridge.syllabus.dto;

import lombok.*;

import java.time.LocalDate;

/**
 * Request DTO for updating a sub-module
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateSubmoduleRequest {
    private String name;
    private String description;
    private Integer displayOrder;

    // Scheduling fields
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer weekNumber;
}
