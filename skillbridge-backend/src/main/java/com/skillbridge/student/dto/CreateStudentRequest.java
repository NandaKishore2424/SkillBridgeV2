package com.skillbridge.student.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateStudentRequest {
    private Long collegeId;
    private String email;
    private String password;
    private String fullName;
    private String rollNumber;
    private String degree;
    private String branch;
    private Integer year;
    private String phone;
}
