package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentSkillDTO {
    private Long skillId;
    private String skillName;
    private String skillCategory;
    private Integer proficiencyLevel;
}
