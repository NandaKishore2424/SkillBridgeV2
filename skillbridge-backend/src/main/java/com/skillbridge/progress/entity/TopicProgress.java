package com.skillbridge.progress.entity;

import com.skillbridge.progress.domain.ProgressStatus;
import com.skillbridge.student.entity.Student;
import com.skillbridge.syllabus.entity.SyllabusTopic;
import com.skillbridge.trainer.entity.Trainer;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * One student's status on one syllabus topic.
 *
 * <p>The {@code topic_progress} table has existed since V1 and had no Java code
 * behind it until now; V17 brought it up to the shape this entity needs.
 */
@Entity
@Table(name = "topic_progress", uniqueConstraints = @UniqueConstraint(
        name = "uk_topic_progress", columnNames = { "student_id", "syllabus_topic_id" }))
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TopicProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "syllabus_topic_id", nullable = false)
    private SyllabusTopic topic;

    /**
     * Denormalised from topic to submodule to module to batch.
     *
     * <p>Without it, "all progress for student X in batch Y" — the single hottest
     * read in this module — is a four-table join. With it, it is one index
     * lookup on {@code idx_progress_student_batch}. A database trigger recomputes
     * it on every insert, so it cannot drift even if some future code path
     * forgets to set it.
     */
    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "college_id", nullable = false)
    private Long collegeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ProgressStatus status = ProgressStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private Trainer updatedBy;

    @Column(name = "comment", columnDefinition = "TEXT")
    private String comment;

    /** 0–100, optional. Null means graded pass/fail with no numeric score. */
    @Column(name = "score")
    private Integer score;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /**
     * Two trainers grading the same student on the same topic at once is rare but
     * real — a grading grid is exactly the kind of screen people leave open.
     * Optimistic locking turns the silent lost update into a 409.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * The only way status changes.
     *
     * <p>Keeps the lifecycle timestamps consistent with the status. A COMPLETED
     * row with a null {@code completed_at}, or a PENDING row that still carries
     * one from a previous grading, is the sort of data bug that is invisible
     * until someone builds a report on it.
     */
    public void record(ProgressStatus newStatus, Trainer trainer, String comment, Integer score) {
        if (newStatus == null) {
            throw new IllegalArgumentException("Progress status is required");
        }
        if (score != null && (score < 0 || score > 100)) {
            throw new IllegalArgumentException("Score must be between 0 and 100");
        }

        ProgressStatus previous = this.status;

        this.status = newStatus;
        this.updatedBy = trainer;
        this.comment = comment;
        this.score = score;

        if (previous == ProgressStatus.PENDING && newStatus != ProgressStatus.PENDING) {
            this.startedAt = LocalDateTime.now();
        }

        // Moving away from COMPLETED clears the completion timestamp rather than
        // leaving a stale one behind.
        this.completedAt = newStatus == ProgressStatus.COMPLETED ? LocalDateTime.now() : null;
    }

    /** Contribution of this row to a weighted completion percentage. */
    public double weight() {
        return status.weight();
    }
}
