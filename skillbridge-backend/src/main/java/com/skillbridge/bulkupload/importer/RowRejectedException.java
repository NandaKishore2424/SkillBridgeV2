package com.skillbridge.bulkupload.importer;

/**
 * A row that cannot be imported, with a reason written for the admin. Its
 * message is shown on screen as it is, so it must never carry a raw driver or
 * parser message.
 */
public class RowRejectedException extends RuntimeException {

    public RowRejectedException(String message) {
        super(message);
    }
}
