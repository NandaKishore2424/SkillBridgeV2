package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The request is well formed but conflicts with the current state of the
 * resource: a duplicate row, an illegal state transition, or a concurrent edit.
 *
 * <p>Distinct from {@link BusinessRuleException} (422) in that a conflict is
 * usually resolvable by the client refreshing and retrying, whereas a business
 * rule violation will fail again identically.
 */
public class ConflictException extends ApiException {

    public ConflictException(String message) {
        super(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public ConflictException(String errorCode, String message) {
        super(HttpStatus.CONFLICT, errorCode, message);
    }
}
