package com.skillbridge.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A server-side fault the code noticed and named, as opposed to one that escaped
 * as a raw exception.
 *
 * <p>Reserved for genuine "this should be impossible" conditions: a role row
 * missing from a table the migrations are supposed to seed, a hashing algorithm
 * the JVM claims not to support. These are deployment or environment faults, not
 * anything the caller did, so the client gets a 500 and the log gets a stack
 * trace — {@link #isLoggable()} is inherited and already true for 5xx.
 *
 * <p>If a caller could have avoided the failure by sending something different,
 * this is the wrong class.
 */
public class InternalServerException extends ApiException {

    public InternalServerException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message);
    }

    public InternalServerException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message, null, cause);
    }
}
