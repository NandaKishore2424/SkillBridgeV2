package com.skillbridge.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillbridge.auth.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.ContentCachingResponseWrapper;
import org.springframework.web.util.WebUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The Stripe-style idempotency contract, on handlers annotated {@link Idempotent}.
 *
 * <table>
 *   <caption>What each case returns</caption>
 *   <tr><th>Situation</th><th>Result</th></tr>
 *   <tr><td>No {@code Idempotency-Key} header</td><td>400 — the header is required</td></tr>
 *   <tr><td>Key unseen</td><td>claimed, handler runs, response stored</td></tr>
 *   <tr><td>Key seen, same body, finished</td><td>the stored response, with {@code Idempotent-Replay: true}</td></tr>
 *   <tr><td>Key seen, same body, still running</td><td>409 with {@code Retry-After}</td></tr>
 *   <tr><td>Key seen, different body or endpoint</td><td>422</td></tr>
 * </table>
 *
 * <p><b>The 422 is the case worth having.</b> Replaying is the visible feature,
 * but the failure it prevents is a client bug: a key reused for a genuinely
 * different request would otherwise be served the first request's response, and
 * the caller would believe an operation succeeded that never ran. Refusing is
 * the only safe answer, because there is no way to tell which of the two
 * requests the caller meant.
 *
 * <p><b>On expiry.</b> A record past {@code expires_at} is treated as absent
 * even though the nightly purge has not reached it yet, so the boundary is the
 * timestamp rather than whenever the job last ran. Otherwise the guarantee
 * would quietly depend on a cron schedule.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyInterceptor implements HandlerInterceptor {

    /** Set on the request so {@code afterCompletion} knows which row to finish. */
    static final String RECORD_ID = IdempotencyInterceptor.class.getName() + ".recordId";

    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (!(handler instanceof HandlerMethod method)
                || method.getMethodAnnotation(Idempotent.class) == null) {
            return true;
        }

        String key = request.getHeader(IdempotencyKeyFilter.HEADER);
        if (key == null || key.isBlank()) {
            return reject(response, 400, "IDEMPOTENCY_KEY_REQUIRED",
                    "This endpoint requires an " + IdempotencyKeyFilter.HEADER + " header. "
                            + "Use a UUID, and send the same one when retrying.");
        }
        if (key.length() > 128) {
            return reject(response, 400, "IDEMPOTENCY_KEY_TOO_LONG",
                    "An " + IdempotencyKeyFilter.HEADER + " may be at most 128 characters.");
        }

        Long userId = SecurityUtils.currentUser().getId();
        String endpoint = request.getMethod() + " " + request.getRequestURI();
        String bodyHash = sha256(bodyOf(request));

        Optional<IdempotencyKey> found = idempotencyService.find(key, userId);
        if (found.isPresent()) {
            IdempotencyKey record = found.get();
            if (record.getExpiresAt().isAfter(LocalDateTime.now())) {
                return handleExisting(response, record, endpoint, bodyHash);
            }
            // Past its window. Ignoring it is not enough -- the row still holds
            // the unique constraint, and the insert below would collide with it.
            idempotencyService.discard(record.getId());
        }

        IdempotencyKey claimed;
        try {
            claimed = idempotencyService.claim(key, userId, endpoint, bodyHash);
        } catch (DataIntegrityViolationException lostTheRace) {
            // An identical request claimed the key between the lookup above and
            // this insert. The constraint is what makes exactly one of us win.
            return inProgress(response);
        }

        request.setAttribute(RECORD_ID, claimed.getId());
        return true;
    }

    private boolean handleExisting(HttpServletResponse response, IdempotencyKey record,
                                   String endpoint, String bodyHash) throws Exception {
        if (!record.getRequestHash().equals(bodyHash)) {
            return reject(response, 422, "IDEMPOTENCY_KEY_REUSED",
                    "This " + IdempotencyKeyFilter.HEADER
                            + " was already used with a different request body.");
        }
        if (!record.getEndpoint().equals(endpoint)) {
            return reject(response, 422, "IDEMPOTENCY_KEY_REUSED",
                    "This " + IdempotencyKeyFilter.HEADER + " was already used for "
                            + record.getEndpoint() + ".");
        }
        if (record.isInProgress()) {
            return inProgress(response);
        }

        response.setStatus(record.getResponseStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Idempotent-Replay", "true");
        if (record.getResponseBody() != null) {
            response.getWriter().write(record.getResponseBody());
        }
        return false;
    }

    /**
     * Finishes the record: stores the response, or drops the claim.
     *
     * <p>A 5xx or a thrown exception releases the key instead of storing it. The
     * operation may or may not have taken effect, and the honest answer to an
     * unknown outcome is to let the client retry — storing the 500 would replay
     * the failure for 24 hours, and leaving the row {@code IN_PROGRESS} would
     * answer every retry with 409 for just as long.
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        Object recordId = request.getAttribute(RECORD_ID);
        if (recordId == null) {
            return;
        }
        try {
            if (ex != null || response.getStatus() >= 500) {
                idempotencyService.release((Long) recordId);
                return;
            }
            idempotencyService.complete((Long) recordId, response.getStatus(), responseBody(response));
        } catch (Exception failure) {
            // Never let bookkeeping fail a request that already succeeded. The
            // row is left IN_PROGRESS and the nightly purge collects it.
            log.error("Could not finish idempotency record {}; it will expire on its own", recordId, failure);
        }
    }

    private static byte[] bodyOf(HttpServletRequest request) {
        IdempotencyKeyFilter.CachedBodyRequest cached =
                WebUtils.getNativeRequest(request, IdempotencyKeyFilter.CachedBodyRequest.class);
        return cached != null ? cached.body() : new byte[0];
    }

    private static String responseBody(HttpServletResponse response) {
        ContentCachingResponseWrapper cached =
                WebUtils.getNativeResponse(response, ContentCachingResponseWrapper.class);
        return cached == null ? null
                : new String(cached.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private boolean inProgress(HttpServletResponse response) throws Exception {
        response.setHeader("Retry-After", "2");
        return reject(response, 409, "REQUEST_IN_PROGRESS",
                "An identical request is still being processed.");
    }

    /** Writes the project's error shape and stops the chain. */
    private boolean reject(HttpServletResponse response, int status, String code, String message)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", java.time.Instant.now().toString());
        body.put("status", status);
        body.put("error", code);
        body.put("message", message);

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }
}
