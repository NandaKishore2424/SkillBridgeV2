package com.skillbridge.trainer.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTrainerProfileRequest {
    private String fullName;
    private String phone;
    private String department;
    private String specialization;
    private String bio;
    private String linkedinUrl;
    private Integer yearsOfExperience;
}
