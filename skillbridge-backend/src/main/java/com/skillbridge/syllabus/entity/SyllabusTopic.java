package com.skillbridge.syllabus.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Entity representing a topic within a syllabus sub-module
 * Topics are the smallest unit of curriculum content
 */
@Entity
@Table(name = "syllabus_topics", uniqueConstraints = {
        @UniqueConstraint(name = "uk_submodule_display_order", columnNames = { "submodule_id", "display_order" })
}, indexes = {
        @Index(name = "idx_topics_submodule", columnList = "submodule_id"),
        @Index(name = "idx_topics_completed", columnList = "is_completed")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyllabusTopic {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submodule_id", nullable = false)
    private SyllabusSubmodule submodule;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "is_completed", nullable = false)
    @Builder.Default
    private Boolean isCompleted = false;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Toggle the completion status of this topic
     */
    public void toggleCompletion() {
        this.isCompleted = !this.isCompleted;
        this.completedAt = this.isCompleted ? LocalDateTime.now() : null;
    }

    /**
     * Mark this topic as completed
     */
    public void markAsCompleted() {
        this.isCompleted = true;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Mark this topic as not completed
     */
    public void markAsNotCompleted() {
        this.isCompleted = false;
        this.completedAt = null;
    }
}
