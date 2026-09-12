package com.skillbridge.common.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes idempotency records past their retention window.
 *
 * <p>Without it the table grows by one row per protected write forever, and its
 * unique index with it. Expiry is not only about size though: a key whose
 * record is gone can be used again, which is what stops a client that recycles
 * key values from being refused indefinitely.
 *
 * <p>The purge is not what makes expiry correct. {@link IdempotencyInterceptor}
 * ignores a record past {@code expires_at} whether or not this job has run, so
 * the guarantee is the timestamp and this is only housekeeping — which matters,
 * because otherwise a cron job that quietly stopped would silently extend every
 * key's lifetime.
 *
 * <p><b>Single instance only.</b> Like {@code EnrollmentMaintenanceJob}, this
 * fires on every node. A double run is harmless here — the second finds nothing
 * left to delete — but a distributed lock is needed before this becomes
 * multi-node with anything less forgiving.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class IdempotencyPurgeJob {

    private final IdempotencyService idempotencyService;
    private final com.skillbridge.common.scheduling.SingleRunGuard singleRun;

    /**
     * Nightly at 03:20, off the hour for the same reason the enrollment job is:
     * every cron in every system is written on the hour, and clustering them is
     * how a quiet database gets a thundering herd at midnight.
     */
    @Scheduled(cron = "0 20 3 * * *")
    public void purgeExpiredKeys() {
        try {
            // One instance, not all of them. The delete is idempotent, so two
            // runs are survivable -- but two instances deleting the same rows at
            // the same moment is contention on a table the request path writes
            // to, for no benefit.
            singleRun.runExclusively("idempotency-purge", () -> {
                int deleted = idempotencyService.purgeExpired();
                log.debug("Idempotency purge finished; removed {} expired keys", deleted);
            });
        } catch (Exception ex) {
            // Spring's default error handler drops a throwing @Scheduled method
            // silently, so the failure is logged here explicitly.
            log.error("Idempotency purge job failed", ex);
        }
    }
}
