package com.skillbridge.trainer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateTrainerRequest {
    private Long collegeId;
    private String email;
    private String password;
    private String fullName;
    private String phone;
    private String department;
    private String specialization;
}
