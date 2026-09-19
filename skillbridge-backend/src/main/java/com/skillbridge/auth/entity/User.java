package com.skillbridge.auth.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_email", columnList = "email"),
        @Index(name = "idx_users_college_id", columnList = "college_id"),
        @Index(name = "idx_users_is_active", columnList = "is_active")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "college_id")
    private Long collegeId; // NULL for SYSTEM_ADMIN

    @Column(name = "email", unique = true, nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    // Account management fields for bulk upload feature
    @Column(name = "must_change_password")
    @Builder.Default
    private Boolean mustChangePassword = false;

    @Column(name = "account_status", length = 20)
    @Builder.Default
    private String accountStatus = "ACTIVE"; // PENDING_SETUP, ACTIVE, INCOMPLETE, SUSPENDED

    @Column(name = "invitation_sent_at")
    private LocalDateTime invitationSentAt;

    @Column(name = "first_login_at")
    private LocalDateTime firstLoginAt;

    @Column(name = "profile_completed")
    @Builder.Default
    private Boolean profileCompleted = false;

    /**
     * Eager on purpose: authentication needs the roles on every request, and a
     * lazy collection here would mean a fetch join on every auth path or a
     * LazyInitializationException on the ones that forgot.
     *
     * <p>{@code @BatchSize} is what makes that affordable. Without it Hibernate
     * issues one {@code user_roles} select <em>per user</em>, so loading a page
     * of 15 students cost 15 extra round trips — measured at 19 statements and
     * 3.4s for {@code GET /admin/students}, against a database whose own
     * execution time for the same work is under a millisecond. With it, the
     * roles for a whole page load in a single {@code where user_id in (...)}.
     *
     * <p>The deeper fix is LAZY plus explicit fetch joins on the two or three
     * paths that genuinely need roles. This is the change that does not risk
     * authentication.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    @BatchSize(size = 100)
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
