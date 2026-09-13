package com.skillbridge.syllabus.service;

import com.skillbridge.common.cache.L1CacheConfig;
import com.skillbridge.syllabus.dto.SyllabusModuleDTO;
import com.skillbridge.testsupport.IntegrationTest;
import com.skillbridge.testsupport.QueryCountAssertion;
import com.skillbridge.testsupport.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A cold key hit by many callers at once must produce one database read, not
 * one per caller.
 *
 * <p>This is Phase 06 Task 5.1 — the thundering herd — and the phase reaches
 * straight for a Redis lock. At L1 the answer is one word, and getting it wrong
 * is the default: <b>plain {@code @Cacheable} does not protect against a
 * stampede.</b> Spring's interception is look-up, invoke, put — three separate
 * steps — so every concurrent caller misses, every one runs the method, and
 * every one writes the same value back. It is only with {@code sync = true} that
 * Spring routes through {@code Cache.get(key, Callable)}, which Caffeine
 * implements as an atomic per-key compute: one caller loads and the rest block
 * on that key and take its answer.
 *
 * <p>The difference does not show up in any single-threaded test, which is why
 * this one exists and why it was run against the unsynchronised code first. What
 * it reported there is worth recording, because the first answer was misleading:
 *
 * <ol>
 *   <li><b>Unsynchronised, with the cache advisor inside the transaction</b> —
 *       4 statements, so 2 loads, reproduced on four consecutive runs. Mild, and
 *       mild for the wrong reason: callers queued for a connection <em>before</em>
 *       consulting the cache, so the pool was throttling the herd. A limiter
 *       nobody intended.</li>
 *   <li><b>Unsynchronised, with the cache advisor moved outside the transaction</b>
 *       (where it belongs, so a hit costs no connection) — the herd reaches the
 *       cache together, all of it misses, all of it demands a connection, and
 *       the test does not fail with a number. It <b>errors</b>:
 *       {@code CannotCreateTransactionException: Could not open JPA
 *       EntityManager for transaction}. That is the thundering herd taking the
 *       pool down, on two runs out of two.</li>
 *   <li><b>{@code sync = true}</b> — 2 statements, three runs out of three.</li>
 * </ol>
 *
 * <p>The two fixes are coupled, which is the part worth carrying away: making a
 * cache hit free of a connection is what makes the stampede dangerous, and
 * {@code sync = true} is what makes it safe. Doing the first without the second
 * would have been a regression dressed as an optimisation.
 *
 * <p>It matters most here precisely because these reads are slow: a statement
 * costs a round trip to another region, so a herd on a cold curriculum key is
 * not N cheap queries but N × 2 round trips arriving together at a pool of
 * twelve connections.
 */
@SpringBootTest
@IntegrationTest
class CurriculumStampedeTest {

    private static final String COLLEGE_CODE = "STAMPEDE";

    /** Enough to be a herd; small enough that a failure is slow rather than endless. */
    private static final int CALLERS = 200;

    @Autowired private CurriculumReader reader;
    @Autowired private QueryCountAssertion queries;
    @Autowired private CacheManager cacheManager;
    @Autowired private JdbcTemplate jdbc;

    private TenantFixture fixture;
    private Long batchId;

    @BeforeEach
    void seed() {
        fixture = new TenantFixture(jdbc, COLLEGE_CODE);
        fixture.seed(1, 1, 2);
        batchId = fixture.batchIds.get(0);
        cacheManager.getCache(L1CacheConfig.CURRICULUM).clear();
    }

    @AfterEach
    void cleanUp() {
        fixture.remove();
        cacheManager.getCache(L1CacheConfig.CURRICULUM).clear();
    }

    @Test
    @DisplayName("200 callers arriving together on a cold key cause one load, not 200")
    void aColdKeyIsLoadedOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(32);
        // Every caller waits on the same latch, so they are released together
        // rather than trickling in and finding the key already warm -- which is
        // what a naive loop would measure, and it would pass either way.
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(CALLERS);

        try {
            var result = queries.countQueries(() -> {
                List<Future<List<SyllabusModuleDTO>>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < CALLERS; i++) {
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        release.await();
                        return reader.byBatchId(batchId);
                    }));
                }
                try {
                    ready.await(30, TimeUnit.SECONDS);
                    release.countDown();
                    List<List<SyllabusModuleDTO>> all = new java.util.ArrayList<>();
                    for (var f : futures) {
                        all.add(f.get(120, TimeUnit.SECONDS));
                    }
                    return all;
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });

            assertThat(result.value()).hasSize(CALLERS);
            assertThat(result.value())
                    .as("every caller has to get the tree, or a low query count means "
                            + "they all failed rather than all hit")
                    .allSatisfy(tree -> assertThat(tree).hasSize(2));

            assertThat(result.queryCount())
                    .as("""
                        Two statements: the modules and the topics, once. Spring's \
                        plain @Cacheable is look-up, invoke, put -- three steps, no \
                        lock -- so every concurrent caller misses and every one \
                        loads. Measured without sync = true, this test does not \
                        report a larger number; it errors with \
                        CannotCreateTransactionException, because the herd exhausts \
                        the connection pool. If you are reading this because the \
                        count came back higher than 2, sync = true has been dropped \
                        from CurriculumReader.""")
                    .isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }
}
