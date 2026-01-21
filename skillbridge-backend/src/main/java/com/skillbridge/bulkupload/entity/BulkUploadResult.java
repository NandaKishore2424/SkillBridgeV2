package com.skillbridge.bulkupload.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "bulk_upload_results")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkUploadResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bulk_upload_id", nullable = false)
    private BulkUpload bulkUpload;

    @Column(name = "row_number", nullable = false)
    private Integer rowNumber;

    @Column(name = "status", nullable = false, length = 20)
    private String status; // SUCCESS, FAILED, SKIPPED

    @Column(name = "entity_id")
    private Long entityId; // student_id or trainer_id if created

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "data", columnDefinition = "TEXT")
    private String data; // JSON string of row data for audit
}
