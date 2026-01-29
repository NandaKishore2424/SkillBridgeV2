package com.skillbridge.enrollment.entity;

import com.skillbridge.auth.entity.User;
import com.skillbridge.batch.entity.Batch;
import com.skillbridge.student.entity.Student;
import com.skillbridge.trainer.entity.Trainer;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Entity representing a trainer's request to add/remove students from a batch
 */
@Entity
@Table(name = "enrollment_requests", indexes = {
        @Index(name = "idx_requests_batch", columnList = "batch_id"),
        @Index(name = "idx_requests_status", columnList = "status"),
        @Index(name = "idx_requests_trainer", columnList = "trainer_id"),
        @Index(name = "idx_requests_student", columnList = "student_id")
})
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trainer_id", nullable = false)
    private Trainer trainer;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private RequestType requestType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RequestStatus status = RequestStatus.PENDING;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

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

    public enum RequestStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    /**
     * Approve this enrollment request
     */
    public void approve(User admin) {
        this.status = RequestStatus.APPROVED;
        this.reviewedBy = admin;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * Reject this enrollment request
     */
    public void reject(User admin) {
        this.status = RequestStatus.REJECTED;
        this.reviewedBy = admin;
        this.reviewedAt = LocalDateTime.now();
    }

    /**
     * Check if this request is pending
     */
    public boolean isPending() {
        return this.status == RequestStatus.PENDING;
    }
}
