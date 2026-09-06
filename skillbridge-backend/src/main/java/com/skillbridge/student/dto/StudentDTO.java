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
    private Boolean isActive; // Added: from user.isActive
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
    /**
     * Batches this student is enrolled in. The list page renders a count from it.
     *
     * <p>The React list page has read this field since it was written; the
     * backend never sent it, so the column showed "None" for every row
     * regardless of the data. Populated for list responses in one grouped
     * query per page — never per row.
     */
    private List<Long> enrolledBatchIds;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
