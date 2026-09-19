package com.skillbridge.bulkupload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One trainer row of a CSV import. Limits match the columns they are written to. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainerUploadDTO {

    @NotBlank(message = "Full Name is required")
    @Size(max = 255, message = "Full Name is longer than 255 characters")
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email is not a valid address")
    @Size(max = 255, message = "Email is longer than 255 characters")
    private String email;

    @Size(max = 100, message = "Department is longer than 100 characters")
    private String department;

    // trainers.specialization is TEXT; the cap only keeps one row sane.
    @Size(max = 1000, message = "Specialization is longer than 1000 characters")
    private String specialization;
}
