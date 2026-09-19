package com.skillbridge.trainer.entity;

import com.skillbridge.auth.entity.User;
import com.skillbridge.college.entity.College;
import jakarta.persistence.*;
import org.hibernate.annotations.SQLRestriction;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

@Entity
@Table(name = "trainers")
@SQLRestriction("deleted_at IS NULL")
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Trainer {
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

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "specialization", columnDefinition = "TEXT")
    private String specialization;

    /**
     * Everything this row is searchable by, lowercased and space-joined:
     * {@code full_name}, {@code department}, {@code specialization} and the
     * owning login's email.
     *
     * <p><b>Read-only. The database computes it.</b> Same design, and the same
     * reason, as {@link com.skillbridge.student.entity.Student#getSearchText()}
     * -- the trainer search had the identical shape, an OR spanning the
     * {@code trainers}/{@code users} join that no index could serve.
     *
     * <p>See {@code db/schema/2026-09-09-search-text.sql}.
     */
    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    @Column(name = "bio", columnDefinition = "TEXT")
    private String bio;

    @Column(name = "linkedin_url", length = 255)
    private String linkedinUrl;

    @Column(name = "years_of_experience")
    private Integer yearsOfExperience;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

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
