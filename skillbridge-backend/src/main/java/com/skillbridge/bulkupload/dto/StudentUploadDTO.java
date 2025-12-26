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
public class StudentUploadDTO {

    @CsvBindByName(column = "Full Name", required = true)
    @NotBlank(message = "Full name is required")
    private String fullName;

    @CsvBindByName(column = "Email", required = true)
    @Email(message = "Invalid email format")
    @NotBlank(message = "Email is required")
    private String email;

    @CsvBindByName(column = "Roll Number", required = true)
    @NotBlank(message = "Roll number is required")
    private String rollNumber;

    @CsvBindByName(column = "Degree")
    private String degree;

    @CsvBindByName(column = "Branch")
    private String branch;

    @CsvBindByName(column = "Year")
    private Integer year;
}
