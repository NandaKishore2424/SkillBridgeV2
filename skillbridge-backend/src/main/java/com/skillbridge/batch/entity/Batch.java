package com.skillbridge.batch.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skillbridge.college.entity.College;
import com.skillbridge.company.entity.Company;
import com.skillbridge.trainer.entity.Trainer;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "batches")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Batch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id", nullable = false)
    @JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
    private College college;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "UPCOMING"; // UPCOMING, OPEN, ACTIVE, COMPLETED, CANCELLED

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    // Many-to-Many relationship with Trainers
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "batch_trainers", joinColumns = @JoinColumn(name = "batch_id"), inverseJoinColumns = @JoinColumn(name = "trainer_id"))
    @JsonIgnoreProperties({ "hibernateLazyInitializer", "handler", "batches" })
    @Builder.Default
    private Set<Trainer> trainers = new HashSet<>();

    // Many-to-Many relationship with Companies
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "batch_companies", joinColumns = @JoinColumn(name = "batch_id"), inverseJoinColumns = @JoinColumn(name = "company_id"))
    @JsonIgnoreProperties({ "hibernateLazyInitializer", "handler", "batches" })
    @Builder.Default
    private Set<Company> companies = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
