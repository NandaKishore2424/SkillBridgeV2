package com.skillbridge.bulkupload.importer;

import com.skillbridge.common.exception.BadRequestException;

/**
 * The file as a whole cannot be imported: not UTF-8, a missing or unknown
 * column, too many rows, broken quoting. Raised while the admin's request is
 * still open, so they see why at once instead of finding a FAILED upload later.
 */
public class InvalidImportFileException extends BadRequestException {

    public InvalidImportFileException(String message) {
        super(message);
    }
}
