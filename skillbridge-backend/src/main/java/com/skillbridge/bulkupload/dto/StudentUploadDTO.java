package com.skillbridge.bulkupload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One student row of a CSV import. Limits match the columns they are written to. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentUploadDTO {

    @NotBlank(message = "Full Name is required")
    @Size(max = 255, message = "Full Name is longer than 255 characters")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email is not a valid address")
    @Size(max = 255, message = "Email is longer than 255 characters")
    private String email;

    @NotBlank(message = "Roll Number is required")
    @Size(max = 50, message = "Roll Number is longer than 50 characters")
    private String rollNumber;

    @Size(max = 100, message = "Degree is longer than 100 characters")
    private String degree;

    @Size(max = 100, message = "Branch is longer than 100 characters")
    private String branch;

    @Min(value = 1, message = "Year must be between 1 and 8")
    @Max(value = 8, message = "Year must be between 1 and 8")
    private Integer year;
}
