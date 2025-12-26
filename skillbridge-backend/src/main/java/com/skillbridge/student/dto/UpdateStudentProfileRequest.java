package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateStudentProfileRequest {
    private String fullName;
    private String phone;
    private String degree;
    private String branch;
    private Integer year;
    private String githubUrl;
    private String portfolioUrl;
    private String bio;
}
