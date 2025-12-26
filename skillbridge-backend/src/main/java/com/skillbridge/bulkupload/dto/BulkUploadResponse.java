package com.skillbridge.bulkupload.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkUploadResponse {
    private Long uploadId;
    private Integer totalRows;
    private Integer successfulRows;
    private Integer failedRows;
    private List<UploadError> errors;
    private String status;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UploadError {
        private Integer rowNumber;
        private String errorMessage;
        private Map<String, String> rowData;
    }
}
