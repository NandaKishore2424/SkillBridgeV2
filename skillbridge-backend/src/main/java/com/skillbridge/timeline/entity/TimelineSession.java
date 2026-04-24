package com.skillbridge.timeline.entity;

import com.skillbridge.batch.entity.Batch;
import com.skillbridge.syllabus.entity.SyllabusTopic;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Entity representing a timeline session for a batch
 */
@Entity
@Table(name = "batch_timeline_sessions", uniqueConstraints = {
        @UniqueConstraint(name = "uk_batch_session_number", columnNames = { "batch_id", "session_number" })
}, indexes = {
        @Index(name = "idx_sessions_batch", columnList = "batch_id"),
        @Index(name = "idx_sessions_topic", columnList = "topic_id"),
        @Index(name = "idx_sessions_date", columnList = "planned_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TimelineSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

    @Column(name = "session_number", nullable = false)
    private Integer sessionNumber;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "topic_id")
    private SyllabusTopic topic;

    @Column(name = "planned_date")
    private LocalDate plannedDate;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
