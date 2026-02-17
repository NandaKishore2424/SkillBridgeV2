package com.skillbridge.student.dto;

import jakarta.validation.constraints.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for updating student profile during initial setup or profile edit
 * Follows Single Responsibility Principle - handles only profile data transfer
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StudentProfileUpdateDTO {

    @NotBlank(message = "Full name is required")
    @Size(max = 255, message = "Full name must not exceed 255 characters")
    private String fullName;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^[+]?[0-9]{10,15}$", message = "Phone number must be valid (10-15 digits)")
    private String phone;

    @NotBlank(message = "Degree is required")
    @Size(max = 100, message = "Degree must not exceed 100 characters")
    private String degree;

    @NotBlank(message = "Branch is required")
    @Size(max = 100, message = "Branch must not exceed 100 characters")
    private String branch;

    @NotNull(message = "Year is required")
    @Min(value = 1, message = "Year must be between 1 and 5")
    @Max(value = 5, message = "Year must be between 1 and 5")
    private Integer year;

    @NotBlank(message = "Roll number is required")
    @Size(max = 50, message = "Roll number must not exceed 50 characters")
    private String rollNumber;

    @Size(max = 1000, message = "Bio must not exceed 1000 characters")
    private String bio;

    @Pattern(regexp = "^(https?://)?(www\\.)?(github\\.com/)[a-zA-Z0-9_-]+/?$", message = "GitHub URL must be a valid GitHub profile URL", flags = Pattern.Flag.CASE_INSENSITIVE)
    private String githubUrl;

    @Pattern(regexp = "^(https?://).*$", message = "Portfolio URL must be a valid URL starting with http:// or https://", flags = Pattern.Flag.CASE_INSENSITIVE)
    private String portfolioUrl;

    @Pattern(regexp = "^(https?://).*$", message = "Resume URL must be a valid URL starting with http:// or https://", flags = Pattern.Flag.CASE_INSENSITIVE)
    private String resumeUrl;
}
