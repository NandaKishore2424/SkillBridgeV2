package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * The request is syntactically valid and the caller is entitled to make it, but
 * a domain rule forbids it: applying to a full batch, grading a topic that is
 * not in the student's syllabus, enrolling a student from another college.
 *
 * <p>422 rather than 400, because there is nothing wrong with the request
 * <em>as a request</em> — the server understood it perfectly and declined.
 * Retrying it unchanged will always fail the same way.
 */
public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "BUSINESS_RULE_VIOLATION", message);
    }

    public BusinessRuleException(String errorCode, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message);
    }

    public BusinessRuleException(String errorCode, String message, Map<String, String> details) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, message, details);
    }
}
