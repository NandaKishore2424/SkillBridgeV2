package com.skillbridge.bulkupload.dto;

import com.skillbridge.bulkupload.entity.BulkUpload;

import java.time.LocalDateTime;

/**
 * One upload, with the counts an admin needs to decide what to do next.
 *
 * @param successfulRows accounts created, including those whose email failed
 * @param emailFailedRows accounts created whose invitation was not sent
 * @param errorReport    why the whole upload failed, if it did
 */
public record BulkUploadDetailDTO(Long id, String entityType, String fileName, String status,
                                  Integer totalRows, Integer successfulRows, Integer failedRows,
                                  long emailFailedRows, String errorReport,
                                  LocalDateTime createdAt, LocalDateTime completedAt) {

    public static BulkUploadDetailDTO from(BulkUpload u, long emailFailedRows) {
        return new BulkUploadDetailDTO(u.getId(), u.getEntityType(), u.getFileName(), u.getStatus(),
                u.getTotalRows(), u.getSuccessfulRows(), u.getFailedRows(), emailFailedRows,
                u.getErrorReport(), u.getCreatedAt(), u.getCompletedAt());
    }
}
