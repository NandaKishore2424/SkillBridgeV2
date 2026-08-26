package com.skillbridge.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Base class for every exception this application throws deliberately.
 *
 * <p>The problem it solves: the codebase previously signalled all 133 of its
 * failure cases with {@code new RuntimeException("Student not found")}. Two
 * things follow from that, and both are bad. First, the caller cannot tell a
 * missing row from a permission failure from a genuine bug, so everything
 * becomes a 500. Second, the global handler had to echo {@code ex.getMessage()}
 * back to the client to say anything useful at all, which leaks internal detail
 * on paths that were never meant to be public.
 *
 * <p>An {@code ApiException} carries its own HTTP status and a stable machine
 * readable code. The handler needs no knowledge of the domain: it reads the
 * status off the exception. Anything that is <em>not</em> an {@code ApiException}
 * reaching the handler is by definition an unexpected bug, gets logged with its
 * stack trace, and returns a generic 500 with no internal detail.
 *
 * <p>The {@code errorCode} is part of the API contract. Clients branch on it;
 * message text is for humans and may be reworded at any time.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    /** Optional structured context, surfaced under {@code details} in the response. */
    private final Map<String, String> details;

    protected ApiException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, null, null);
    }

    protected ApiException(HttpStatus status, String errorCode, String message,
                           Map<String, String> details) {
        this(status, errorCode, message, details, null);
    }

    protected ApiException(HttpStatus status, String errorCode, String message,
                           Map<String, String> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
        this.details = details;
    }

    /**
     * Whether this failure is worth a stack trace in the logs.
     *
     * <p>A 404 for a row a user guessed at is routine and logging its stack
     * trace at ERROR trains everyone to ignore the error log. A 500 always
     * deserves one. Subclasses that represent server-side faults override this.
     */
    public boolean isLoggable() {
        return status.is5xxServerError();
    }
}
