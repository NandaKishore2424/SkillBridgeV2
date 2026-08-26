package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The caller is authenticated but not permitted to perform this action on a
 * resource they are otherwise allowed to know about.
 *
 * <p>Do not use this for cross-tenant access. Confirming "this batch exists but
 * is not yours" tells an attacker which ids are real; throw
 * {@link ResourceNotFoundException} there instead. This is for the case where
 * the caller can legitimately see the resource but lacks the right to act on it
 * — a trainer trying to delete a batch, say.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
