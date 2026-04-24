package com.skillbridge.student.dto;

import com.skillbridge.batch.dto.BatchDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class RecommendedBatchDTO extends BatchDTO {
    private Double matchScore;
    private List<String> matchReasons;
    private List<String> trainerNames;
    private List<String> companyNames;
    private Integer enrolledCount;
    private Integer maxEnrollments;
}
