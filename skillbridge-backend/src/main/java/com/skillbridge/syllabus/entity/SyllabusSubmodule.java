package com.skillbridge.syllabus.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a sub-module within a curriculum module
 * Sub-modules group related topics and provide scheduling granularity
 */
@Entity
@Table(name = "syllabus_submodules", uniqueConstraints = {
        @UniqueConstraint(name = "uk_module_submodule_order", columnNames = { "module_id", "display_order" })
}, indexes = {
        @Index(name = "idx_submodules_module", columnList = "module_id"),
        @Index(name = "idx_submodules_order", columnList = "module_id, display_order")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyllabusSubmodule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private SyllabusModule module;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    // Scheduling fields
    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "week_number")
    private Integer weekNumber;

    @OneToMany(mappedBy = "submodule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<SyllabusTopic> topics = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Helper methods for bidirectional relationship
    public void addTopic(SyllabusTopic topic) {
        topics.add(topic);
        topic.setSubmodule(this);
    }

    public void removeTopic(SyllabusTopic topic) {
        topics.remove(topic);
        topic.setSubmodule(null);
    }

    // Computed fields for DTOs
    public int getTopicsCount() {
        return topics != null ? topics.size() : 0;
    }

    public long getCompletedTopicsCount() {
        return topics != null
                ? topics.stream().filter(SyllabusTopic::getIsCompleted).count()
                : 0;
    }
}
