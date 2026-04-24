package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * DTO for returning complete student profile information
 * Used after profile updates to provide full student details to frontend
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentProfileDTO {

    private Long id;
    private Long userId;
    private Long collegeId;
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
    private String accountStatus;
    private Boolean profileCompleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
