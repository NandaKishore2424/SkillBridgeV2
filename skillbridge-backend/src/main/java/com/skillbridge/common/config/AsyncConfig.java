package com.skillbridge.common.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async execution and scheduling.
 *
 * <p>{@code @EnableScheduling} is what makes {@code @Scheduled} methods run at
 * all — without it they are inert and fail silently, which is a genuinely
 * annoying thing to debug.
 *
 * <p><b>Both pools are sized by what the database can absorb, not by a formula.</b>
 * Phase 07 § 2.1 gives Little's Law — {@code cores × utilisation × (1 +
 * wait/service)} — which for CSV row processing works out near 58 threads, and
 * then names the mistake that number invites: sizing from the formula and
 * ignoring the downstream constraint. Here the constraint is unusually hard.
 * Supabase's pooler allows this <em>project</em> fifteen server connections, of
 * which the backend takes twelve and interactive traffic needs most of them
 * (docs/CONNECTION_POOL.md). Fifty-eight upload threads against that would leave
 * fifty permanently queued and starve every request on the site while a single
 * CSV imported.
 */
@Configuration
@EnableAsync
@EnableScheduling
@Slf4j
public class AsyncConfig implements org.springframework.scheduling.annotation.AsyncConfigurer {

    /**
     * What happens when an {@code @Async void} method throws.
     *
     * <p>Nothing, by default, that anybody would find. The caller has already
     * been given control back, so there is nowhere to propagate to; Spring's
     * default handler logs the stack trace and not the method or its arguments,
     * which for a bulk upload means a trace with no upload id in it. The job row
     * stays at "processing" and the only clue is an orphan stack trace.
     *
     * <p>This names the method and its arguments, so the log line says which
     * upload died. Arguments are the ids the async signatures carry -- an upload
     * id, a college id -- and not user data.
     */
    @Override
    public org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
            getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error(
                "Async method {}.{} failed with arguments {}",
                method.getDeclaringClass().getSimpleName(), method.getName(),
                java.util.Arrays.stream(params)
                        .map(p -> p instanceof byte[] bytes ? bytes.length + " bytes" : String.valueOf(p))
                        .toList(),
                ex);
    }

    /**
     * Carries AI events to RabbitMQ after the publishing transaction commits.
     *
     * <p>Deliberately small and bounded. The work is one AMQP publish; the
     * point of the executor is only to get the network call off the thread
     * that is still holding a database connection, not to add throughput.
     *
     * <p>The rejection policy is the default {@code AbortPolicy} rather than
     * {@code CallerRunsPolicy}: caller-runs would hand the publish back to the
     * committing thread, which is precisely the thread whose connection we are
     * trying to release. A rejected event is logged and dropped, which matches
     * what {@code AIEventPublisher} already does when the broker is down.
     */
    @Bean(name = "aiEventExecutor")
    public Executor aiEventExecutor(MeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ai-event-");

        // Finish what is queued before the JVM goes. Thirty rather than sixty:
        // these are single AMQP publishes, so a queue that cannot drain in half a
        // minute is a broker that is not coming back, and holding the shutdown
        // open for it only delays the deploy.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return monitored(registry, executor, "aiEventExecutor");
    }

    /**
     * Bulk CSV import: one row at a time against a database a round trip away.
     *
     * <p>Four threads, and the number comes from the connection ceiling rather
     * than from throughput. Each row does its own read-check-insert, so a thread
     * here holds a connection for most of its life; four is a third of the pool,
     * which is as much as a background job may take from a pool that also has to
     * serve every page on the site. Raising it does not import faster once the
     * pool is the bottleneck — it just moves the queue from this executor into
     * Hikari, where it is invisible and where interactive traffic queues behind
     * it.
     */
    @Bean(name = "bulkUploadExecutor")
    public Executor bulkUploadExecutor(MeterRegistry registry) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("bulk-upload-");
        executor.setKeepAliveSeconds(120);

        // CallerRunsPolicy, not the default AbortPolicy.
        //
        // The default throws, and because submission happens on the caller's
        // thread it throws *there* -- into the controller handling the upload,
        // which answers 500. Loud rather than silent, so the phase's description
        // of a task vanishing is not what happens here; it is still the wrong
        // answer, because an administrator's upload is refused outright on
        // account of somebody else's being in progress.
        //
        // Caller-runs makes the submitting thread do the work instead. That is
        // back-pressure: the submitter stops producing until the queue drains,
        // and nothing is dropped or refused. ExecutorSaturationTest holds both
        // behaviours side by side.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // A deploy sends SIGTERM. Without this the JVM exits with rows inserted,
        // more rows not, and a bulk_upload row that never reaches a terminal
        // state -- so the screen shows "processing" forever and nobody knows how
        // much of the file landed.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();
        return monitored(registry, executor, "bulkUploadExecutor");
    }

    /**
     * Publishes queue depth, active threads and task timings under
     * {@code executor.*}.
     *
     * <p>An unmonitored pool is one whose saturation is invisible until a user
     * reports it. Queue depth in particular is the early warning: it climbs long
     * before anything is rejected, and it is the number that says whether the
     * sizing above is right.
     */
    private static Executor monitored(MeterRegistry registry,
                                      ThreadPoolTaskExecutor executor,
                                      String name) {
        ExecutorServiceMetrics.monitor(registry, executor.getThreadPoolExecutor(), name);
        return executor;
    }
}
