package com.skillbridge.syllabus.dto;

import lombok.*;

/**
 * Request DTO for updating a module
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateModuleRequest {
    private String name;
    private String description;
    private Integer displayOrder;
}
