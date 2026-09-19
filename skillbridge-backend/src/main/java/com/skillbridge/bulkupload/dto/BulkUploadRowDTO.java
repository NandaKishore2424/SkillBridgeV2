package com.skillbridge.bulkupload.dto;

import java.util.Map;

/**
 * One row of an upload as the admin needs to see it.
 *
 * @param rowNumber the row in the file, header being row 1
 * @param status    SUCCESS, EMAIL_FAILED or FAILED
 * @param message   why it failed, in words meant for the admin
 * @param values    what the row said, column by column
 * @param userId    the account created, for "Resend invitation"; null if none
 */
public record BulkUploadRowDTO(int rowNumber, String status, String message, Map<String, String> values,
                               Long userId) {
}
