package com.skillbridge.common.scheduling;

import com.skillbridge.testsupport.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exactly one instance runs a scheduled job, even when several try at once.
 *
 * <p>The failure this prevents has no symptom until there are two replicas, at
 * which point every nightly job runs twice simultaneously. That is survivable
 * for the two jobs here and not survivable for the next one somebody adds, so
 * the guard goes in before the second replica rather than after the incident.
 *
 * <p>Two threads on two connections is the closest this can get to two
 * instances, and it is close enough: an advisory lock is held by a database
 * <em>session</em>, and two pooled connections are two sessions. Nothing about
 * the lock knows or cares that they share a JVM.
 */
@SpringBootTest
@IntegrationTest
class SingleRunGuardTest {

    @Autowired private SingleRunGuard guard;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("two instances racing for the same job produce one run")
    void onlyOneInstanceRuns() throws Exception {
        String job = "test-job-" + System.nanoTime();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothReady = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        AtomicInteger declined = new AtomicInteger();

        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    bothReady.countDown();
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    boolean mine = guard.runExclusively(job, () -> {
                        ran.incrementAndGet();
                        // Hold the lock long enough that the other thread is
                        // certain to meet it held rather than already released.
                        sleep(800);
                    });
                    if (!mine) {
                        declined.incrementAndGet();
                    }
                });
            }

            assertThat(bothReady.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                    .as("a thread still waiting means the guard blocked instead of declining")
                    .isTrue();

            assertThat(ran.get())
                    .as("""
                        One run. pg_try_advisory_xact_lock returns false rather than \
                        waiting, so the instance that loses the race declines instead of \
                        queueing -- queueing would run the job twice in sequence, which \
                        for a nightly reconciliation is the same bug an hour later.""")
                    .isEqualTo(1);
            assertThat(declined.get()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("the lock is gone once the job finishes, so tomorrow's run is not blocked")
    void theLockIsReleasedWithTheTransaction() {
        String job = "test-release-" + System.nanoTime();

        assertThat(guard.runExclusively(job, () -> { })).isTrue();
        assertThat(guard.runExclusively(job, () -> { }))
                .as("""
                    Transaction-scoped, so there is no release to forget and no path -- \
                    exception, timeout, a killed instance -- that leaves it held. A \
                    lock row in a table would need a lease and an expiry sweeper to \
                    make the same promise.""")
                .isTrue();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = ?",
                Integer.class, (int) SingleRunGuard.lockKeyFor(job)))
                .as("nothing should still be holding this key")
                .isZero();
    }

    @Test
    @DisplayName("different jobs do not block each other")
    void jobsAreIndependent() {
        String first = "test-a-" + System.nanoTime();
        String second = "test-b-" + System.nanoTime();

        assertThat(SingleRunGuard.lockKeyFor(first))
                .as("one key for every job would serialise the whole schedule")
                .isNotEqualTo(SingleRunGuard.lockKeyFor(second));

        assertThat(guard.runExclusively(first, () -> { })).isTrue();
        assertThat(guard.runExclusively(second, () -> { })).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
