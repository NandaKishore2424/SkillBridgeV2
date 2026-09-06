package com.skillbridge.company.dto;

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
public class CompanyDTO {
    private Long id;
    private Long collegeId;
    private String collegeName;
    private String name;
    private String domain;
    private String hiringType;
    /**
     * Batches this company is linked to. The list page renders a count from it.
     *
     * <p>The React list page has read this field since it was written; the
     * backend never sent it, so the column showed "None" for every row
     * regardless of the data. Populated for list responses in one grouped
     * query per page — never per row.
     */
    private List<Long> linkedBatchIds;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
