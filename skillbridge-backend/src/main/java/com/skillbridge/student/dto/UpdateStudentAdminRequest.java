package com.skillbridge.student.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin edit of a student's academic details.
 *
 * <p>Every field is optional and null means "leave unchanged", so a client can
 * send only what it is actually changing. That is why there is no
 * {@code @NotBlank} anywhere here — a blank string is a different intent from an
 * absent one, and {@code @Size(min = 1)} rejects it without also rejecting null.
 */
@Data
public class UpdateStudentAdminRequest {

    @Size(min = 1, max = 50, message = "Roll number cannot be blank")
    private String rollNumber;

    @Size(min = 1, max = 100, message = "Degree cannot be blank")
    private String degree;

    @Size(min = 1, max = 100, message = "Branch cannot be blank")
    private String branch;

    @Min(value = 1, message = "Year must be between 1 and 5")
    @Max(value = 5, message = "Year must be between 1 and 5")
    private Integer year;
}
