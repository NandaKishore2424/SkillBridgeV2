package com.skillbridge.trainer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainerDTO {
    private Long id;
    private Long userId;
    private String email;
    private Boolean isActive; // Added: from user.isActive
    private String fullName;
    private String phone;
    private String department;
    private String specialization;
    private String bio;
    private String linkedinUrl;
    private Integer yearsOfExperience;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
