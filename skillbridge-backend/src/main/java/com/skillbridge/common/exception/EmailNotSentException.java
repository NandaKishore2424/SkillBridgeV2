package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The change was saved, but the email that goes with it was not accepted by
 * the mail provider. 502: the failure is upstream, and the request may simply
 * be repeated.
 */
public class EmailNotSentException extends ApiException {

    public EmailNotSentException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "EMAIL_NOT_SENT", message, null, cause);
    }
}
