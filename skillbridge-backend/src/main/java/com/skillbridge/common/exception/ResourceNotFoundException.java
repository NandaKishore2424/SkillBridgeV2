package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

/**
 * The requested resource does not exist, or the caller is not entitled to know
 * that it exists.
 *
 * <p>That second clause is deliberate. A cross-tenant read returns 404 rather
 * than 403, because a 403 confirms to an attacker that the id they guessed is
 * real and belongs to someone. Callers in tenant-scoped code should reach for
 * this, not {@link ForbiddenException}.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", message);
    }

    /**
     * Convenience for the overwhelmingly common case, producing messages like
     * {@code "Batch 42 not found"} without every call site inventing its own
     * phrasing.
     */
    public static ResourceNotFoundException of(String resource, Object id) {
        return new ResourceNotFoundException(resource + " " + id + " not found");
    }
}
