package com.skillbridge.bulkupload.dto;

import com.opencsv.bean.CsvBindByName;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainerUploadDTO {

    @CsvBindByName(column = "Full Name", required = true)
    @NotBlank(message = "Full name is required")
    private String fullName;

    @CsvBindByName(column = "Email", required = true)
    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;

    @CsvBindByName(column = "Department")
    private String department;

    @CsvBindByName(column = "Specialization")
    private String specialization;
}
