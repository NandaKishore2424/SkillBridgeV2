package com.skillbridge.enrollment.entity;

import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.enrollment.domain.EnrollmentStatus;
import com.skillbridge.enrollment.domain.RequestSource;
import com.skillbridge.student.entity.Student;
import com.skillbridge.trainer.entity.Trainer;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * A request for someone to be enrolled in, or removed from, a batch.
 *
 * <p>Covers both a trainer asking an admin to move a student and a student
 * applying for themselves — {@link RequestSource} distinguishes them, and
 * {@code trainer} is null for the latter.
 */
@Entity
@Table(name = "enrollment_requests", indexes = {
        @Index(name = "idx_requests_batch", columnList = "batch_id"),
        @Index(name = "idx_requests_status", columnList = "status"),
        @Index(name = "idx_requests_trainer", columnList = "trainer_id"),
        @Index(name = "idx_requests_student", columnList = "student_id")
})
@FilterDef(name = "collegeFilter", parameters = @ParamDef(name = "collegeId", type = Long.class))
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EnrollmentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    /** Null when a student applied for themselves. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trainer_id")
    private Trainer trainer;

    /**
     * Denormalised from the batch so the tenant filter can be expressed at all —
     * a Hibernate {@code @Filter} condition is raw SQL against this table and
     * cannot traverse the join to {@code batches}.
     */
    @Column(name = "college_id", nullable = false)
    private Long collegeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    @Builder.Default
    private RequestSource source = RequestSource.TRAINER_REQUEST;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private EnrollmentStatus status = EnrollmentStatus.PENDING;

    /** Why the requester asked. Supplied at creation. */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** Why the reviewer decided as they did. Supplied at review. */
    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    /**
     * Guards the approve/reject race.
     *
     * <p>Two admins open the pending queue and both click Approve. Without this,
     * both updates succeed, both try to create an {@code Enrollment}, and the
     * unique constraint turns the loser into an opaque
     * {@code DataIntegrityViolationException}. With it the second update matches
     * zero rows and Hibernate raises {@code OptimisticLockingFailureException},
     * which the global handler renders as a 409 telling the admin to refresh.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum RequestType {
        ADD,
        REMOVE
    }

    /**
     * The single place a status changes.
     *
     * <p>Every caller goes through here, so the transition rule, the timestamp and
     * the reviewer can never get out of step with each other. The rule itself
     * lives on {@link EnrollmentStatus}, so this method does not grow a branch
     * every time a state is added.
     */
    public void transitionTo(EnrollmentStatus target, User actor, String decisionReason) {
        status.assertCanTransitionTo(target);

        this.status = target;
        this.reviewedBy = actor;
        this.reviewedAt = LocalDateTime.now();

        if (decisionReason != null && !decisionReason.isBlank()) {
            this.decisionReason = decisionReason;
        }
    }

    public boolean isPending() {
        return this.status == EnrollmentStatus.PENDING;
    }

    /** True when a student applied for themselves rather than a trainer asking. */
    public boolean isStudentApplication() {
        return this.source == RequestSource.STUDENT_APPLICATION;
    }
}
