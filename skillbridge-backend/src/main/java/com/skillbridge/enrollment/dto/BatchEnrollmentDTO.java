package com.skillbridge.enrollment.dto;

import lombok.*;

import java.util.List;

/**
 * DTO containing batch enrollment information
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchEnrollmentDTO {
    private Long batchId;
    private String batchName;
    private List<EnrolledStudentDTO> enrolledStudents;
    private Integer enrolledCount;
}
