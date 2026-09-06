package com.skillbridge.company.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skillbridge.college.entity.College;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

@Entity
@Table(name = "companies")
@SQLRestriction("deleted_at IS NULL")
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id", nullable = false)
    @JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
    private College college;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "domain", length = 100)
    private String domain;

    @Column(name = "hiring_type", length = 20)
    private String hiringType; // FULL_TIME, INTERNSHIP, BOTH

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

    /**
     * Soft delete marker. Null means live.
     *
     * <p>Set rather than issuing a DELETE, because a hard delete here cascades
     * through users, enrollments, progress and the audit trail — unrecoverable,
     * and for academic records not something to do casually. The
     * {@code activeFilter} hides these rows from every query that goes through
     * a repository; a deliberate read of deleted data has to disable it.
     */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    /** Who deleted it. Kept so "who removed this student?" is answerable. */
    @Column(name = "deleted_by")
    private Long deletedBy;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
