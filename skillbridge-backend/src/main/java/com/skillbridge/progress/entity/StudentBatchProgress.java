package com.skillbridge.progress.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * Rolled-up progress for one (student, batch) pair.
 *
 * <p>A pure derived value: everything here can be recomputed from
 * {@code topic_progress} at any time. It exists because the student dashboard
 * renders a completion percentage for every enrolled batch, and computing that
 * live means aggregating every topic row on every page load — for every student,
 * on the most-visited screen in the product.
 *
 * <p>The trade is the usual one for a denormalised read model: reads get much
 * cheaper, and writes take on the duty of keeping this correct. Recomputation is
 * driven by {@code ProgressService} on every grading operation, and the whole
 * row can be rebuilt from scratch if it is ever suspected of being wrong.
 */
@Entity
@Table(name = "student_batch_progress", uniqueConstraints = @UniqueConstraint(
        name = "uk_student_batch_progress", columnNames = { "student_id", "batch_id" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StudentBatchProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "college_id", nullable = false)
    private Long collegeId;

    @Column(name = "topics_total", nullable = false)
    @Builder.Default
    private Integer topicsTotal = 0;

    @Column(name = "topics_completed", nullable = false)
    @Builder.Default
    private Integer topicsCompleted = 0;

    @Column(name = "topics_in_progress", nullable = false)
    @Builder.Default
    private Integer topicsInProgress = 0;

    @Column(name = "topics_needs_work", nullable = false)
    @Builder.Default
    private Integer topicsNeedsWork = 0;

    /** Weighted, not a raw completed/total ratio. See {@code ProgressStatus.weight()}. */
    @Column(name = "weighted_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal weightedPercent = BigDecimal.ZERO;

    /** Mean of the non-null scores. Null when nothing has been scored numerically. */
    @Column(name = "average_score", precision = 5, scale = 2)
    private BigDecimal averageScore;

    @Column(name = "last_activity_at")
    private LocalDateTime lastActivityAt;

    @Column(name = "recomputed_at", nullable = false)
    @Builder.Default
    private LocalDateTime recomputedAt = LocalDateTime.now();

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * Replace every counter from a freshly computed aggregate.
     *
     * <p>Deliberately a whole-row replacement rather than an incremental
     * adjustment. Incremental updates to a denormalised counter drift the moment
     * one code path forgets to call them, and the drift is silent; recomputing
     * from source is a little more work and cannot be wrong.
     */
    public void apply(int total, int completed, int inProgress, int needsWork,
                      double weightedFraction, Double avgScore, LocalDateTime lastActivity) {
        this.topicsTotal = total;
        this.topicsCompleted = completed;
        this.topicsInProgress = inProgress;
        this.topicsNeedsWork = needsWork;
        this.weightedPercent = BigDecimal.valueOf(weightedFraction * 100)
                .setScale(2, RoundingMode.HALF_UP);
        this.averageScore = avgScore == null ? null
                : BigDecimal.valueOf(avgScore).setScale(2, RoundingMode.HALF_UP);
        this.lastActivityAt = lastActivity;
        this.recomputedAt = LocalDateTime.now();
    }
}
