package com.skillbridge.common.scheduling;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a scheduled job on exactly one instance, using the database already here.
 *
 * <p><b>The problem.</b> {@code @Scheduled} fires on every node. One instance
 * today, so nothing has gone wrong yet, and the moment a second replica exists
 * every nightly job runs twice at the same moment. Idempotent jobs survive that;
 * anything that appends, emails, charges or reconciles does not, and the failure
 * arrives at 02:15 on the night somebody scales the deployment.
 *
 * <p><b>Why an advisory lock rather than ShedLock or Redis.</b> Phase 07 § 4
 * reaches for Redis, and this application has none — deliberately, see
 * {@code docs/CACHING_STRATEGY.md} § 7. Postgres has the primitive built in and
 * it is a better fit than either: {@code pg_try_advisory_lock} is held by the
 * <em>session</em>, so it is released when the transaction ends and, crucially,
 * also when the connection dies. A lock row in a table needs a lease, a clock
 * and an expiry sweeper to survive an instance being killed mid-job; this needs
 * none of them, because a dead instance has no session.
 *
 * <p><b>Try, never wait.</b> {@code pg_try_advisory_xact_lock} returns false
 * rather than blocking. A job that waits for the lock runs twice in sequence,
 * which for a nightly reconciliation is the same bug an hour later. Declining is
 * the correct outcome: another instance is already doing it.
 *
 * <p>The lock is transaction-scoped, so there is no release to forget and no
 * path — exception, timeout, {@code kill -9} — that leaves it held.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SingleRunGuard {

    private final JdbcTemplate jdbc;

    /**
     * Runs {@code job} if this instance can take the lock for {@code name}.
     *
     * <p>{@code REQUIRES_NEW} because the lock's lifetime is the transaction's:
     * joining a caller's transaction would tie the lock to whatever else that
     * transaction is doing, and a scheduled method normally has no transaction
     * at all, in which case the lock would be released the instant it was taken.
     *
     * @return true if this instance ran the job
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean runExclusively(String name, Runnable job) {
        Boolean acquired = jdbc.queryForObject(
                "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKeyFor(name));

        if (!Boolean.TRUE.equals(acquired)) {
            log.info("Skipping scheduled job '{}': another instance holds the lock", name);
            return false;
        }

        log.debug("Running scheduled job '{}' under advisory lock", name);
        job.run();
        return true;
    }

    /**
     * A stable 64-bit key for a job name.
     *
     * <p>Advisory locks are keyed by number, not by string, and the number is
     * global to the database — so the mapping has to be stable across restarts
     * and unlikely to collide with anything else using advisory locks on the same
     * instance. {@code String.hashCode} is 32 bits and specified by the language,
     * so it is stable; widening it with a fixed namespace keeps job keys away
     * from the low integers somebody else would reach for by hand.
     */
    static long lockKeyFor(String name) {
        return ((long) "skillbridge".hashCode() << 32) | (name.hashCode() & 0xffffffffL);
    }
}
