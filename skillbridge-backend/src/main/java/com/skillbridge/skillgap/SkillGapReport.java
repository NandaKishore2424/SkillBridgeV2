package com.skillbridge.skillgap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A student's latest skill-gap analysis (V7). Written by the AI service, never
 * by this application, hence {@code @Immutable} and no setters.
 */
@Entity
@Table(name = "skill_gap_reports")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SkillGapReport {

    @Id
    @Column(name = "student_id")
    private Long studentId;

    @Column(name = "college_id", nullable = false)
    private Long collegeId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    /** The contracts/skill-gap-report document, as JSON text; parsed by {@link SkillGapReportDocument}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String report;

    @Column(name = "source_event_id")
    private UUID sourceEventId;

    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;
}
