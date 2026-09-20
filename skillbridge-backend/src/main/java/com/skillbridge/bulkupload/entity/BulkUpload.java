package com.skillbridge.bulkupload.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.skillbridge.auth.entity.User;
import com.skillbridge.college.entity.College;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

@Entity
@Table(name = "bulk_uploads")
@Filter(name = "collegeFilter", condition = "college_id = :collegeId")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties({ "hibernateLazyInitializer", "handler" })
public class BulkUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id", nullable = false)
    private College college;

    /** Null once that account is deleted: the upload's history outlives it (V9). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by_user_id")
    private User uploadedBy;

    @Column(name = "entity_type", nullable = false, length = 20)
    private String entityType; // STUDENT, TRAINER

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "total_rows", nullable = false)
    private Integer totalRows;

    @Column(name = "successful_rows", nullable = false)
    @Builder.Default
    private Integer successfulRows = 0;

    @Column(name = "failed_rows", nullable = false)
    @Builder.Default
    private Integer failedRows = 0;

    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private String status = "PROCESSING"; // PROCESSING, COMPLETED, FAILED

    @Column(name = "error_report", columnDefinition = "TEXT")
    private String errorReport;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** SHA-256 of the uploaded bytes, hex. Unique per college and entity type unless FAILED (V5). */
    @Column(name = "file_sha256", length = 64)
    private String fileSha256;

    /** The importer's heartbeat; see {@code StaleUploadSweeper}. */
    @Column(name = "last_progress_at")
    private LocalDateTime lastProgressAt;
}
