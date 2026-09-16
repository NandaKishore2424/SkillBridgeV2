package com.skillbridge.shared.messaging.deadletter;

import com.skillbridge.common.scheduling.SingleRunGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Removes resolved dead letters past {@code dead-letters.retention-days}.
 *
 * <p>Only REPLAYED and DISCARDED rows. A PENDING row is a decision nobody has made
 * yet, and deleting it on a timer would be the DLQ losing messages after all, just
 * more slowly.
 */
@Component
@Slf4j
public class DeadLetterPurgeJob {

    private final DeadLetterService deadLetters;
    private final SingleRunGuard singleRun;
    private final int retentionDays;

    public DeadLetterPurgeJob(DeadLetterService deadLetters, SingleRunGuard singleRun,
                              @Value("${dead-letters.retention-days:30}") int retentionDays) {
        this.deadLetters = deadLetters;
        this.singleRun = singleRun;
        this.retentionDays = retentionDays;
    }

    /** 03:25, off the hour and clear of the outbox (03:15) and idempotency (03:20) purges. */
    @Scheduled(cron = "0 25 3 * * *")
    public void purge() {
        try {
            singleRun.runExclusively("dead-letter-purge", () -> {
                int deleted = deadLetters.purgeResolvedBefore(Instant.now().minus(Duration.ofDays(retentionDays)));
                log.info("Purged {} resolved dead letters older than {} days", deleted, retentionDays);
            });
        } catch (Exception e) {
            // Spring's scheduler drops a thrown exception without logging it.
            log.error("Dead letter purge failed", e);
        }
    }
}
