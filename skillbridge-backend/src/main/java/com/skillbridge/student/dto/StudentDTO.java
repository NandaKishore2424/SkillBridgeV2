package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentDTO {
    private Long id;
    private Long userId;
    private String email;
    private String fullName;
    private String rollNumber;
    private String degree;
    private String branch;
    private Integer year;
    private String phone;
    private String githubUrl;
    private String portfolioUrl;
    private String resumeUrl;
    private String bio;
    private List<StudentSkillDTO> skills;
    private List<StudentProjectDTO> projects;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
