package com.skillbridge.progress.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Outcome of a bulk grading call.
 *
 * <p>Reports per-student outcomes rather than a bare success flag. A trainer who
 * pasted a stale student list needs to know <em>which</em> three of forty were
 * skipped, not that "some" were.
 */
@Data
@Builder
public class BulkGradeResultDTO {

    private Long topicId;
    private String topicName;

    private int requested;
    private int graded;

    /** Students who are not enrolled, or have no progress row for this topic. */
    private List<Long> skippedStudentIds;

    private String message;
}
