package com.skillbridge.common.audit;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/**
 * One security-relevant action: who did what, when, from where, and whether it
 * was allowed.
 *
 * <p>There is deliberately no foreign key from {@code actorUserId} to
 * {@code users}. An audit trail whose rows disappear when an account is deleted
 * is worthless precisely in the case you most want it — which is also why
 * {@code actorEmail} is denormalised rather than joined.
 *
 * <p>Rows are append-only. Nothing in the application updates or deletes one.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();

    /** Null for an action attempted before authentication, e.g. a failed login. */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(name = "college_id")
    private Long collegeId;

    @Column(name = "action", nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", length = 100)
    private String resourceType;

    /** A string, not a Long: resources are identified variously across the app. */
    @Column(name = "resource_id", length = 100)
    private String resourceId;

    /** SUCCESS, FAILURE or DENIED. Constrained by {@code chk_audit_outcome}. */
    @Column(name = "outcome", nullable = false, length = 20)
    private String outcome;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /**
     * Free-form JSON context for the action, held as a String.
     *
     * <p>{@code @JdbcTypeCode(SqlTypes.JSON)} is Hibernate 6's native jsonb
     * binding, so this needs no extra dependency. Kept as a String rather than
     * a Map because nothing reads it structurally yet — it exists to be looked
     * at by a human during an investigation.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private String metadata;

    /** Reserved for Phase 10 tracing. Null until then. */
    @Column(name = "trace_id", length = 64)
    private String traceId;
}
