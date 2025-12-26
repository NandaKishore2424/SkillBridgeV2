package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateStudentProjectRequest {
    private String title;
    private String description;
    private String technologies;
    private String projectUrl;
    private String githubUrl;
    private String startDate; // ISO format
    private String endDate; // ISO format
}
