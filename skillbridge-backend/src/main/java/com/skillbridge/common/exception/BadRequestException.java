package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * The request itself is malformed — a missing field, an unparseable value, an
 * argument outside its permitted range.
 *
 * <p>Use this when the client could fix the request by changing what it sent.
 * If the request is syntactically fine but the domain forbids the operation,
 * that is {@link BusinessRuleException}.
 */
public class BadRequestException extends ApiException {

    public BadRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public BadRequestException(String message, Map<String, String> details) {
        super(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message, details);
    }
}
