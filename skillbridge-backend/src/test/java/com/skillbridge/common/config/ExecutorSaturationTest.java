package com.skillbridge.common.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What happens to work submitted to a full executor.
 *
 * <p>A bulk upload is a user watching a progress bar. If the task that fills it
 * in is dropped, the bar says "processing" until somebody investigates — so what
 * saturation does is the whole behaviour of this pool, and it was never
 * specified: the original executor set a size and a queue and left the rejection
 * policy at its default.
 *
 * <p>These build executors directly rather than through Spring. The question is
 * about {@code ThreadPoolExecutor}'s contract under saturation, and asking it
 * without a container in the way keeps the answer unambiguous — and lets these
 * run on every build rather than only when {@code DATABASE_URL} is set.
 */
class ExecutorSaturationTest {

    /** One worker, one queue slot: saturation arrives at the third submission. */
    private static ThreadPoolTaskExecutor tiny(ThreadPoolExecutor.CallerRunsPolicy policy) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        if (policy != null) {
            executor.setRejectedExecutionHandler(policy);
        }
        executor.initialize();
        return executor;
    }

    @Test
    @DisplayName("the default policy rejects loudly, at the submitting thread")
    void abortPolicyThrowsToTheSubmitter() throws Exception {
        ThreadPoolTaskExecutor executor = tiny(null);
        CountDownLatch hold = new CountDownLatch(1);

        try {
            executor.execute(() -> await(hold));   // occupies the one worker
            executor.execute(() -> await(hold));   // fills the one queue slot

            assertThatThrownBy(() -> executor.execute(() -> { }))
                    .as("""
                        Worth establishing rather than assuming. Phase 07 says a rejected \
                        task is swallowed and the upload silently never happens; it is \
                        not. Submission happens on the caller's thread, so the rejection \
                        is thrown there -- into the controller, which answers 500. Loud \
                        rather than silent, and still wrong: the user's upload was \
                        refused because somebody else's was in progress.""")
                    .isInstanceOf(TaskRejectedException.class);
        } finally {
            hold.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("caller-runs applies back-pressure instead, and loses nothing")
    void callerRunsExecutesTheTaskAnyway() throws Exception {
        ThreadPoolTaskExecutor executor = tiny(new ThreadPoolExecutor.CallerRunsPolicy());
        CountDownLatch hold = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();

        try {
            executor.execute(() -> await(hold));
            executor.execute(() -> await(hold));

            // The third submission has nowhere to go, so the submitting thread
            // runs it itself. That is the back-pressure: the submitter stops
            // producing until the queue drains.
            executor.execute(completed::incrementAndGet);

            assertThat(completed.get())
                    .as("""
                        The task ran, on this thread, before execute returned. Nothing \
                        was dropped and nothing was thrown -- the submitter simply paid \
                        for the work it could not hand off.""")
                    .isEqualTo(1);
        } finally {
            hold.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("the configured pools drain their queues on shutdown")
    void configuredPoolsFinishQueuedWorkBeforeExiting() throws Exception {
        AsyncConfig config = new AsyncConfig();
        Executor executor = config.bulkUploadExecutor(new SimpleMeterRegistry());
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor) executor;

        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            pool.execute(() -> {
                sleep(20);
                done.incrementAndGet();
            });
        }

        // What a deploy does.
        pool.shutdown();

        assertThat(done.get())
                .as("""
                    Without waitForTasksToCompleteOnShutdown a SIGTERM during a deploy \
                    exits with work still queued, and a bulk upload half-way through \
                    leaves rows inserted and a job row that never reaches a terminal \
                    state.""")
                .isEqualTo(20);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
