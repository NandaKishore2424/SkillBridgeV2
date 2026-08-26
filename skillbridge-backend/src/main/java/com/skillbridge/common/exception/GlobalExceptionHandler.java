package com.skillbridge.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Translates exceptions into HTTP responses.
 *
 * <p>Two rules govern everything here.
 *
 * <p><b>1. The client learns only what it needs to fix its request.</b> Anything
 * the server did not anticipate returns a generic message plus a correlation id.
 * The previous version of this class returned {@code ex.getMessage()} for every
 * uncaught {@code RuntimeException}, which meant Hibernate constraint names, SQL
 * fragments and entity {@code toString()} output — including, on one path, a
 * bcrypt hash — were being handed to whoever made the request.
 *
 * <p><b>2. Log severity tracks who is at fault.</b> A 404 or a 422 is a client
 * making an ordinary mistake and logs at DEBUG. A 5xx is our bug and logs at
 * ERROR with a stack trace. An error log that fills with routine 404s is an
 * error log nobody reads.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // ------------------------------------------------------------------
    // Deliberate application failures
    // ------------------------------------------------------------------

    /**
     * Every exception the domain throws on purpose lands here. The status comes
     * off the exception, so adding a new failure mode never means editing this
     * class.
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {

        // A 5xx ApiException is still a server fault, and its message is written
        // for us, not for the caller — several wrap a driver or parser message.
        // Treat it exactly like an unhandled exception: log everything, return a
        // correlation id and nothing else.
        if (ex.getStatus().is5xxServerError()) {
            String correlationId = newCorrelationId();
            log.error("{} [{}] at {} {}: {}", ex.getErrorCode(), correlationId,
                    request.getMethod(), request.getRequestURI(), ex.getMessage(), ex);

            return ResponseEntity.status(ex.getStatus()).body(ErrorResponse.builder()
                    .timestamp(Instant.now())
                    .status(ex.getStatus().value())
                    .error(ex.getErrorCode())
                    .message("Something went wrong on our side. Quote reference " + correlationId
                            + " if you need to report this.")
                    .path(request.getRequestURI())
                    .details(Map.of("correlationId", correlationId))
                    .build());
        }

        log.debug("{} at {}: {}", ex.getErrorCode(), request.getRequestURI(), ex.getMessage());

        return ResponseEntity.status(ex.getStatus()).body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(ex.getStatus().value())
                .error(ex.getErrorCode())
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .details(ex.getDetails())
                .build());
    }

    // ------------------------------------------------------------------
    // Request validation
    // ------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String field = error instanceof FieldError fe ? fe.getField() : error.getObjectName();
            fieldErrors.put(field, error.getDefaultMessage());
        });

        log.debug("Validation failed at {}: {}", request.getRequestURI(), fieldErrors);
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Request validation failed", request, fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER",
                "Required parameter '" + ex.getParameterName() + "' is missing", request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Parameter '" + ex.getName() + "' has an invalid value", request, null);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                               HttpServletRequest request) {
        // Never echo the parser's message: it quotes the offending payload back,
        // which can include credentials the client sent to the wrong endpoint.
        log.debug("Unreadable request body at {}", request.getRequestURI());
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Request body is missing or not valid JSON", request, null);
    }

    /**
     * {@code IllegalArgumentException} is what a lot of pre-existing code throws
     * for bad input, and Spring itself throws it from {@code Enum.valueOf}. It is
     * kept as a 400 for compatibility, but new code should throw
     * {@link BadRequestException} so the error code is meaningful.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                                HttpServletRequest request) {
        log.debug("Illegal argument at {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), request, null);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex,
                                                             HttpServletRequest request) {
        log.warn("Illegal state at {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "INVALID_STATE", ex.getMessage(), request, null);
    }

    // ------------------------------------------------------------------
    // Security
    // ------------------------------------------------------------------

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                             HttpServletRequest request) {
        log.warn("Access denied at {}", request.getRequestURI());
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action", request, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                               HttpServletRequest request) {
        log.debug("Authentication failed at {}", request.getRequestURI());
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required", request, null);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Two admins approved the same request at the same moment; the second write
     * matched zero rows.
     *
     * <p>Worth a specific message. The generic alternative is a 500 that tells
     * the user nothing, and they retry into the same race forever.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrentModification(OptimisticLockingFailureException ex,
                                                                       HttpServletRequest request) {
        log.warn("Concurrent modification at {}: {}", request.getRequestURI(), ex.getMessage());
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "Someone else changed this record while you were working on it. Refresh and try again.",
                request, null);
    }

    /**
     * A unique or foreign key constraint rejected the write.
     *
     * <p>The driver's message names the constraint, the table and often the
     * conflicting value, so it is logged and never returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                              HttpServletRequest request) {
        log.warn("Data integrity violation at {}: {}", request.getRequestURI(), rootMessage(ex));
        return build(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION",
                "This operation conflicts with existing data. The record may already exist.",
                request, null);
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                           HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND",
                "No endpoint matches this path", request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                    HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                ex.getMethod() + " is not supported on this endpoint", request, null);
    }

    // ------------------------------------------------------------------
    // Everything else — by definition a bug
    // ------------------------------------------------------------------

    /**
     * Anything reaching here was not anticipated, so the client gets nothing but
     * a correlation id. That id is logged alongside the stack trace, which is how
     * a support request ("I got error 8f3c...") becomes a single grep.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        String correlationId = newCorrelationId();

        log.error("Unhandled exception [{}] at {} {}", correlationId,
                request.getMethod(), request.getRequestURI(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .error("INTERNAL_ERROR")
                .message("Something went wrong on our side. Quote reference " + correlationId
                        + " if you need to report this.")
                .path(request.getRequestURI())
                .details(Map.of("correlationId", correlationId))
                .build());
    }

    // ------------------------------------------------------------------

    /** Short enough for a user to read over the phone, long enough not to collide in a log. */
    private String newCorrelationId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String code, String message,
                                                 HttpServletRequest request, Map<String, String> details) {
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(code)
                .message(message)
                .path(request.getRequestURI())
                .details(details)
                .build());
    }

    private String rootMessage(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage();
    }
}
