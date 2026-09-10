package com.skillbridge.progress.dto;

import com.skillbridge.progress.domain.ProgressStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One student's row in the trainer's grading grid for a single topic.
 *
 * <p>Separate from {@link TopicProgressDTO} because the two views are
 * transposed. The student detail view lists <em>topics for one student</em>, so
 * each row names its topic and the student is the page. The grading grid lists
 * <em>students for one topic</em>, so each row must name its student and the
 * topic is the page.
 *
 * <p>The grid returned {@code TopicProgressDTO} until 2026-09-10, which made it
 * unusable: every row carried the same topic id and name and differed only by
 * an opaque {@code progressId}. A trainer could not tell whose row was whose,
 * and a client could not build the {@code studentIds} that
 * {@code BulkGradeRequest} requires. That is why no grading screen was ever
 * built against it — the endpoint existed and answered 200, and its answer was
 * not enough to render.
 *
 * <p>Nothing had to change in the query: {@code findGradingGridForTopic}
 * already did {@code JOIN FETCH tp.student}. The student was loaded and then
 * dropped by the mapper.
 */
@Data
@Builder
public class GradingGridRowDTO {

    /** The progress row being graded. */
    private Long progressId;

    private Long studentId;
    private String studentName;
    private String rollNumber;

    private ProgressStatus status;

    /** 0–100, or null for "assessed without a numeric score". */
    private Integer score;

    private String comment;

    private String gradedByTrainerName;
    private LocalDateTime completedAt;
    private LocalDateTime updatedAt;
}
