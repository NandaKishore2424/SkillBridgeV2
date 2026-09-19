package com.skillbridge.bulkupload.dto;

import com.skillbridge.bulkupload.entity.BulkUpload;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One row of bulk-upload history.
 *
 * <p>The history endpoints used to return {@code List<BulkUpload>} — the entity
 * itself. Its {@code college} and {@code uploadedBy} are lazy {@code @ManyToOne}s,
 * and with {@code open-in-view: false} the session is closed before Jackson
 * runs, so serialising the response threw
 * {@code HttpMessageNotWritableException: Could not initialize proxy} and the
 * endpoint returned 500.
 *
 * <p>Returning an entity from a controller has the same problem wherever it
 * happens: the JSON shape is then whatever the persistence model happens to be,
 * and every lazy association on it is a serialisation-time landmine. A DTO makes
 * the wire contract explicit and cannot trigger a load.
 *
 * <p>{@code errorReport} is deliberately excluded — it is a JSON blob of
 * per-row failures that can be large, and belongs behind its own endpoint rather
 * than in every history row.
 */
@Data
@Builder
public class BulkUploadHistoryDTO {

    private Long id;
    private String entityType;
    private String fileName;
    private Integer totalRows;
    private Integer successfulRows;
    private Integer failedRows;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;

    /**
     * Reads no association: every field is a column of {@code bulk_uploads}
     * itself, so this cannot trigger a lazy load, with or without a session.
     */
    public static BulkUploadHistoryDTO from(BulkUpload upload) {
        return BulkUploadHistoryDTO.builder()
                .id(upload.getId())
                .entityType(upload.getEntityType())
                .fileName(upload.getFileName())
                .totalRows(upload.getTotalRows())
                .successfulRows(upload.getSuccessfulRows())
                .failedRows(upload.getFailedRows())
                .status(upload.getStatus())
                .createdAt(upload.getCreatedAt())
                .completedAt(upload.getCompletedAt())
                .build();
    }
}
