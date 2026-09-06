package com.skillbridge.trainer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

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
    /**
     * Batches this trainer is assigned to. The list page renders a count from it.
     *
     * <p>The React list page has read this field since it was written; the
     * backend never sent it, so the column showed "None" for every row
     * regardless of the data. Populated for list responses in one grouped
     * query per page — never per row.
     */
    private List<Long> assignedBatchIds;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
