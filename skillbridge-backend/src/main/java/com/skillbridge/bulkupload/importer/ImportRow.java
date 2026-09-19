package com.skillbridge.bulkupload.importer;

import java.util.Map;

/**
 * One data row, keyed by canonical column name.
 *
 * @param rowNumber as a spreadsheet would show it: the header is row 1
 * @param values    trimmed; a column the file does not have is absent
 * @param problem   set when the row could not be read as a whole (wrong number
 *                  of values); such a row is recorded as failed, not imported
 */
public record ImportRow(int rowNumber, Map<String, String> values, String problem) {
}
