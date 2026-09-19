package com.skillbridge.enrollment.entity;

import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.enrollment.domain.EnrollmentState;
import com.skillbridge.student.entity.Student;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * A student's standing membership of a batch.
 */
@Entity
@Table(name = "enrollments")
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Enrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    /** Denormalised from the batch so the tenant filter can be applied here. */
    @Column(name = "college_id", nullable = false)
    private Long collegeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private EnrollmentState status = EnrollmentState.ACTIVE;

    /**
     * Who created this enrollment — the approving admin, or the student on a
     * self-service application. Null for rows that predate the column.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrolled_by")
    private User enrolledBy;

    @Column(name = "enrolled_at", nullable = false)
    private LocalDateTime enrolledAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @PrePersist
    protected void onCreate() {
        if (enrolledAt == null) {
            enrolledAt = LocalDateTime.now();
        }
    }

    public void complete() {
        this.status = EnrollmentState.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    public void drop() {
        this.status = EnrollmentState.DROPPED;
        this.completedAt = null;
    }

    public boolean isActive() {
        return this.status == EnrollmentState.ACTIVE;
    }
}
