package com.skillbridge.company.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyDTO {
    private Long id;
    private Long collegeId;
    private String collegeName;
    private String name;
    private String domain;
    private String hiringType;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
