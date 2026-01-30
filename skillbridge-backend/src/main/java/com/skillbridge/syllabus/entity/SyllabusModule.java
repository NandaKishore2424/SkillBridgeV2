package com.skillbridge.syllabus.entity;

import com.skillbridge.batch.entity.Batch;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Entity representing a module in a batch curriculum
 * Modules are high-level course sections (e.g., "Core Java", "React")
 */
@Entity
@Table(name = "syllabus_modules", uniqueConstraints = {
        @UniqueConstraint(name = "uk_batch_display_order", columnNames = { "batch_id", "display_order" })
}, indexes = {
        @Index(name = "idx_modules_batch", columnList = "batch_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyllabusModule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", nullable = false)
    private Batch batch;

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

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    @Builder.Default
    private List<SyllabusSubmodule> submodules = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Helper methods for bidirectional relationship
    public void addSubmodule(SyllabusSubmodule submodule) {
        submodules.add(submodule);
        submodule.setModule(this);
    }

    public void removeSubmodule(SyllabusSubmodule submodule) {
        submodules.remove(submodule);
        submodule.setModule(null);
    }
}
