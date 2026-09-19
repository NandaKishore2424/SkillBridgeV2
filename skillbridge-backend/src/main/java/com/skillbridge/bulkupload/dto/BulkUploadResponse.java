package com.skillbridge.bulkupload.dto;

/**
 * The answer to an upload: accepted, and queued or already known.
 *
 * <p>No row errors here. The import runs after this response is sent, so the
 * list this DTO used to carry was always empty; they are at
 * {@code GET /admin/bulk-uploads/{id}/rows}.
 *
 * @param rows            data rows in the file
 * @param alreadyUploaded this exact file was uploaded before and was not
 *                        imported again; {@code uploadId} is that upload
 */
public record BulkUploadResponse(Long uploadId, String status, int rows, boolean alreadyUploaded) {
}
