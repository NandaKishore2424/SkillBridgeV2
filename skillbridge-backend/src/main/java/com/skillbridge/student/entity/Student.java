package com.skillbridge.student.entity;

import com.skillbridge.auth.entity.User;
import com.skillbridge.college.entity.College;
import org.hibernate.annotations.BatchSize;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "students")
@SQLRestriction("deleted_at IS NULL")
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Student {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true, nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id", nullable = false)
    private College college;

    @Column(name = "full_name", nullable = false, length = 255)
    private String fullName;

    @Column(name = "roll_number", nullable = false, length = 50)
    private String rollNumber;

    @Column(name = "degree", length = 100)
    private String degree;

    @Column(name = "branch", length = 100)
    private String branch;

    @Column(name = "year")
    private Integer year;

    /**
     * Everything this row is searchable by, lowercased and space-joined:
     * {@code full_name}, {@code roll_number}, {@code degree}, {@code branch}
     * and the owning login's email.
     *
     * <p><b>Read-only. The database computes it.</b> It is a
     * {@code GENERATED ALWAYS AS ... STORED} column, so writing to it is an
     * error -- hence {@code insertable=false, updatable=false}.
     *
     * <p>It exists because the search predicate ORed those five columns, and
     * one of them lives on {@code users}. A disjunction spanning two relations
     * cannot be pushed to either side, so Postgres joined first and filtered
     * after, reading every user row for every keystroke -- ~50 ms regardless of
     * what was typed, at 50k rows. One column and one GIN index made the same
     * searches 33-87x faster with identical results.
     *
     * <p>Email cannot be part of a generated expression, which may only read
     * the same row, so it is denormalised onto {@code students.user_email} by
     * trigger and this column is generated over that. {@code user_email} is
     * deliberately not mapped here: nothing in Java reads it, and mapping it
     * would load a redundant 255 characters on every student fetched. It is not
     * nullable-hostile either, so {@code ddl-auto: validate} stays happy.
     *
     * <p>See {@code db/schema/2026-09-09-search-text.sql} and
     * {@code docs/INDEX_STRATEGY.md} 2.
     */
    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "github_url", length = 255)
    private String githubUrl;

    @Column(name = "portfolio_url", length = 255)
    private String portfolioUrl;

    @Column(name = "resume_url", length = 255)
    private String resumeUrl;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 25)
    private List<StudentSkill> skills = new ArrayList<>();

    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @BatchSize(size = 25)
    private List<StudentProject> projects = new ArrayList<>();

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
