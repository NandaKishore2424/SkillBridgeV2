package com.skillbridge.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;

/**
 * The single error shape every failing endpoint returns.
 *
 * <p>{@code error} is the stable contract — a machine readable code clients may
 * branch on. {@code message} is prose for humans and may be reworded without
 * notice, so nothing should parse it.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private Instant timestamp;

    private int status;

    /** Stable machine readable code, e.g. {@code RESOURCE_NOT_FOUND}. */
    private String error;

    /** Human readable explanation. Not part of the contract. */
    private String message;

    private String path;

    /** Field level errors, or a correlation id on a 500. Omitted when empty. */
    private Map<String, String> details;
}
