package com.skillbridge.common.audit;

import com.skillbridge.auth.security.AuthenticatedUser;
import com.skillbridge.auth.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * Writes the audit trail.
 *
 * <p>Two properties matter more than anything else here. The first comes from
 * {@link AuditLogWriter} committing each row on a connection of its own (it
 * used to be {@code REQUIRES_NEW}, which starved the pool); the second from the
 * try/catch in {@link #write}:
 *
 * <ul>
 *   <li><b>An audit row survives a rolled-back business transaction.</b> The
 *       most important events to record are the ones that failed or were
 *       denied — and those are exactly the transactions that roll back. Joining
 *       the caller's transaction would erase the record of the very thing it
 *       was written to capture.</li>
 *   <li><b>A failed audit write never fails the request.</b> Every call is
 *       wrapped so that a problem writing the trail is logged and swallowed. An
 *       audit trail is an observation of the system, and an observation that can
 *       break what it observes is worse than no observation.</li>
 * </ul>
 *
 * <p>That second point is a real trade-off, not an oversight: it means the trail
 * can have gaps under database trouble. In a regime where the audit log is a
 * compliance artefact rather than a debugging aid, this should fail closed
 * instead — reject the action if it cannot be recorded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private static final int USER_AGENT_MAX = 500;

    private final AuditLogWriter auditLogWriter;

    /** Records an action performed by the currently authenticated caller. */
    public void record(String action, String resourceType, Object resourceId, String outcome) {
        record(action, resourceType, resourceId, outcome, null);
    }

    public void record(String action, String resourceType, Object resourceId,
                       String outcome, String metadataJson) {
        AuthenticatedUser caller = SecurityUtils.currentUserIfPresent().orElse(null);
        write(AuditLog.builder()
                .actorUserId(caller != null ? caller.getId() : null)
                .actorEmail(caller != null ? caller.getEmail() : null)
                .collegeId(caller != null ? caller.getCollegeId() : null)
                .action(action)
                .resourceType(emptyToNull(resourceType))
                .resourceId(resourceId != null ? String.valueOf(resourceId) : null)
                .outcome(outcome)
                .metadata(metadataJson)
                .build());
    }

    /**
     * Records an action for an actor identified explicitly rather than from the
     * security context.
     *
     * <p>Login is the case that needs this: the account is known by the time the
     * password has been verified, but no principal exists yet, so
     * {@code SecurityUtils} would yield nothing and the row would land with a
     * null {@code actorUserId}. Querying "everything user 42 did" must not miss
     * the login that started the session.
     */
    public void recordFor(Long actorUserId, String actorEmail, Long collegeId,
                          String action, String outcome, String metadataJson) {
        write(AuditLog.builder()
                .actorUserId(actorUserId)
                .actorEmail(actorEmail)
                .collegeId(collegeId)
                .action(action)
                .outcome(outcome)
                .metadata(metadataJson)
                .build());
    }

    /**
     * Records an action by an actor who is not authenticated.
     *
     * <p>Needed for the login endpoints: a failed login has no principal, and
     * the email attempted is the only identifying detail there is. It is stored
     * as {@code actorEmail} with a null {@code actorUserId}, which is also how a
     * login attempt against a non-existent account is represented.
     */
    public void recordAnonymous(String action, String actorEmail, String outcome, String metadataJson) {
        write(AuditLog.builder()
                .actorEmail(actorEmail)
                .action(action)
                .outcome(outcome)
                .metadata(metadataJson)
                .build());
    }

    /**
     * Enriches the entry from the current request and hands it to
     * {@link AuditLogWriter}.
     *
     * <p>The writer uses its own autocommit connection, so a failed insert
     * cannot mark the caller's transaction rollback-only; catching it here is
     * enough to keep it from failing the request.
     */
    private void write(AuditLog entry) {
        try {
            currentRequest().ifPresent(request -> {
                entry.setIpAddress(clientIp(request));
                entry.setUserAgent(truncate(request.getHeader("User-Agent")));
            });
            auditLogWriter.write(entry);
        } catch (Exception ex) {
            // Never propagate. See the class javadoc.
            log.error("Failed to write audit entry action={} outcome={}: {}",
                    entry.getAction(), entry.getOutcome(), ex.getMessage());
        }
    }

    private static Optional<HttpServletRequest> currentRequest() {
        // Null outside a request — a scheduled job, or a test.
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return Optional.of(attrs.getRequest());
        }
        return Optional.empty();
    }

    /**
     * The client address, preferring the first hop in {@code X-Forwarded-For}.
     *
     * <p>That header is client-controlled and trivially spoofed unless a proxy
     * you trust overwrites it. It is recorded here as the best available signal,
     * not as evidence — treat a recorded IP as a hint during an investigation
     * rather than as attribution.
     */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first.length() > 45 ? first.substring(0, 45) : first;
            }
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) {
            return null;
        }
        return userAgent.length() > USER_AGENT_MAX ? userAgent.substring(0, USER_AGENT_MAX) : userAgent;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
