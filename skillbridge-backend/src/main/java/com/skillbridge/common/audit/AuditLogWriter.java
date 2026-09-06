package com.skillbridge.common.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of audit logging, deliberately a separate bean.
 *
 * <p>This exists only so that {@code REQUIRES_NEW} actually takes effect. Had
 * {@link AuditLogService} annotated its own private write method and called it
 * internally, the call would not pass through the Spring proxy and the
 * annotation would be silently ignored — the write would quietly join the
 * caller's transaction and vanish with it on rollback. That failure mode is
 * invisible: the code reads as if it works.
 *
 * <p>A suspended-and-resumed outer transaction is the whole point: the events
 * most worth recording are the failed and denied ones, and those are exactly
 * the transactions that roll back.
 */
@Component
@RequiredArgsConstructor
public class AuditLogWriter {

    private final AuditLogRepository auditLogRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog entry) {
        auditLogRepository.save(entry);
    }
}
