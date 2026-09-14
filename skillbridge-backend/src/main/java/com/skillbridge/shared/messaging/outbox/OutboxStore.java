package com.skillbridge.shared.messaging.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The relay's database side: every method is one short transaction, and none of
 * them touches the broker.
 *
 * <p>That split is the design. {@link OutboxRelay} calls {@link #claimDue} (a
 * transaction), publishes (no transaction), then {@link #recordPublished} or
 * {@link #recordFailure} (a transaction). No connection is held across the
 * network call, which {@code ConnectionHoldingRulesTest} enforces: the relay is
 * not {@code @Transactional}, and nothing here reaches {@code RabbitTemplate}.
 */
@Service
@RequiredArgsConstructor
public class OutboxStore {

    static final int MAX_ERROR_LENGTH = 2000;

    private final OutboxEventRepository repository;

    /**
     * Locks due rows with SKIP LOCKED and leases them, in one transaction.
     *
     * <p>Rows already at {@code maxAttempts} are marked DEAD here rather than
     * leased again. They can only arrive in that state one way: a relay claimed
     * them that many times and never recorded an outcome, which means it crashed
     * each time. Without this check such an event would be claimed for ever, and
     * the promise that every event eventually reaches PUBLISHED or DEAD would be
     * false exactly for the events most in need of a human.
     */
    @Transactional
    public List<ClaimedEvent> claimDue(int limit, Duration lease, int maxAttempts) {
        LocalDateTime now = LocalDateTime.now();
        List<OutboxEvent> due = repository.lockDue(now, limit);
        if (due.isEmpty()) {
            return List.of();
        }

        List<ClaimedEvent> claimed = new ArrayList<>(due.size());
        List<Long> leaseIds = new ArrayList<>(due.size());
        for (OutboxEvent row : due) {
            if (row.getAttempts() >= maxAttempts) {
                repository.markDead(row.getId(),
                        "claimed " + row.getAttempts() + " times with no recorded outcome; "
                                + "the relay most likely crashed while publishing it");
                continue;
            }
            leaseIds.add(row.getId());
            claimed.add(ClaimedEvent.of(row));
        }
        if (!leaseIds.isEmpty()) {
            repository.lease(leaseIds, now.plus(lease));
        }
        return claimed;
    }

    @Transactional
    public void recordPublished(long id) {
        repository.markPublished(id, LocalDateTime.now());
    }

    /**
     * Schedules a retry, or gives up.
     *
     * @return true if the event is now DEAD
     */
    @Transactional
    public boolean recordFailure(ClaimedEvent event, String error, int maxAttempts, Duration retryIn) {
        String reason = truncate(error);
        if (event.attempts() >= maxAttempts) {
            repository.markDead(event.id(), reason);
            return true;
        }
        repository.scheduleRetry(event.id(), reason, LocalDateTime.now().plus(retryIn));
        return false;
    }

    @Transactional
    public int purgePublishedBefore(LocalDateTime cutoff) {
        return repository.deletePublishedBefore(cutoff);
    }

    @Transactional(readOnly = true)
    public long count(OutboxStatus status) {
        return repository.countByStatus(status);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
