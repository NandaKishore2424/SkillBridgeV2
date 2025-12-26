package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AddStudentSkillRequest {
    private Long skillId;
    private Integer proficiencyLevel; // 1-5
}
