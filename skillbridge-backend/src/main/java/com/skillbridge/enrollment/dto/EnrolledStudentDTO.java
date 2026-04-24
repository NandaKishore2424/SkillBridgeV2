package com.skillbridge.enrollment.dto;

import lombok.*;

/**
 * DTO for a student enrolled in a batch
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrolledStudentDTO {
    private Long studentId;
    private String fullName;
    private String rollNumber;
    private String email;
    private String department;
    private Integer year;
}
