package com.skillbridge.trainer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Admin edit of a trainer. Null means "leave unchanged"; see UpdateStudentAdminRequest. */
@Data
public class UpdateTrainerAdminRequest {

    @Size(min = 1, max = 255, message = "Full name cannot be blank")
    private String fullName;

    @Size(max = 20)
    private String phone;

    @Size(max = 100)
    private String department;

    @Size(max = 255)
    private String specialization;

    private String bio;

    @Size(max = 255)
    private String linkedinUrl;

    @Min(value = 0, message = "Years of experience cannot be negative")
    @Max(value = 60, message = "Years of experience looks wrong")
    private Integer yearsOfExperience;
}
