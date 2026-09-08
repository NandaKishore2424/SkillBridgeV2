package com.skillbridge.batch.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skillbridge.college.entity.College;
import com.skillbridge.company.entity.Company;
import com.skillbridge.trainer.entity.Trainer;
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "batches")
@SQLRestriction("deleted_at IS NULL")
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
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

    /**
     * Maximum active enrollments. Null means uncapped.
     *
     * <p>Enforced in {@code StudentEnrollmentService} under a row lock, not by a
     * database constraint — a constraint cannot count rows in another table.
     */
    @Column(name = "capacity")
    private Integer capacity;

    /**
     * Soft delete marker. Every read path must filter on {@code IS NULL}.
     *
     * <p>Batches are referenced by enrollments, progress rows and syllabus trees;
     * a hard delete would either cascade away a student's history or fail on a
     * foreign key. Neither is what an admin means by "remove this batch".
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** Who deleted it. Added alongside the other soft-deletable entities. */
    @Column(name = "deleted_by")
    private Long deletedBy;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * Trainers assigned to this batch.
     *
     * <p>{@code @BatchSize} is the safety net, not the fix. Where a read path
     * needs these it should say so — a fetch join, or a bulk query keyed by the
     * page's ids, both of which this codebase uses. What the annotation buys is
     * what happens when somebody forgets: Hibernate loads the collection for up
     * to 25 batches in one {@code WHERE batch_id IN (?,...)} rather than one
     * query per batch, so an accidental N+1 becomes an N/25+1. Bounded rather
     * than free, which is the difference between a slow page and an incident.
     *
     * <p>Every collection association in this codebase carries one, and
     * {@code PaginationRulesTest} fails the build if a new one does not.
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "batch_trainers", joinColumns = @JoinColumn(name = "batch_id"), inverseJoinColumns = @JoinColumn(name = "trainer_id"))
    @JsonIgnoreProperties({ "hibernateLazyInitializer", "handler", "batches" })
    @Builder.Default
    @BatchSize(size = 25)
    private Set<Trainer> trainers = new HashSet<>();

    // Many-to-Many relationship with Companies
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "batch_companies", joinColumns = @JoinColumn(name = "batch_id"), inverseJoinColumns = @JoinColumn(name = "company_id"))
    @JsonIgnoreProperties({ "hibernateLazyInitializer", "handler", "batches" })
    @Builder.Default
    @BatchSize(size = 25)
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

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Whether students may apply. Enrollment closes once the batch is running. */
    public boolean isOpenForEnrollment() {
        return !isDeleted() && ("OPEN".equals(status) || "UPCOMING".equals(status));
    }
}
